package app.beyoureyes.core.data.cloud

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal data class PasswordRecoveryTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
)

internal fun requirePasswordRecoveryRedirectUrl(value: String) {
    require(value.length in 1..2_048) { "auth redirect URL is too long" }
    val uri = runCatching { URI(value) }.getOrNull()
    require(
        uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.port == -1 && uri.rawQuery == null &&
            uri.rawFragment == null && uri.rawPath == "/auth/callback",
    ) { "auth redirect must be a canonical HTTPS /auth/callback URL" }
}

internal fun parsePasswordRecoveryCallback(
    expectedRedirectUrl: String,
    callbackUrl: String,
): PasswordRecoveryTokens? {
    if (expectedRedirectUrl.length !in 1..2_048 || callbackUrl.length !in 1..40_000) return null
    val expected = runCatching { URI(expectedRedirectUrl) }.getOrNull() ?: return null
    val actual = runCatching { URI(callbackUrl) }.getOrNull() ?: return null
    if (expected.scheme != "https" || actual.scheme != expected.scheme ||
        !actual.host.equals(expected.host, ignoreCase = true) || actual.port != -1 ||
        actual.rawUserInfo != null || actual.rawPath != expected.rawPath ||
        actual.rawQuery != null || actual.rawFragment.isNullOrBlank()
    ) return null
    val values = linkedMapOf<String, String>()
    for (component in actual.rawFragment.split('&')) {
        val separator = component.indexOf('=')
        if (separator <= 0) return null
        val key = decodeFormComponent(component.substring(0, separator)) ?: return null
        val value = decodeFormComponent(component.substring(separator + 1)) ?: return null
        if (key !in RECOVERY_FRAGMENT_KEYS || values.put(key, value) != null) return null
    }
    if (values["type"] != "recovery" || values["token_type"]?.lowercase() != "bearer") return null
    if (values["sb"]?.isNotEmpty() == true) return null
    val access = values["access_token"] ?: return null
    val refresh = values["refresh_token"] ?: return null
    val expiresInSeconds = values["expires_in"]?.toLongOrNull()
        ?.takeIf { it in 1..MAX_RECOVERY_SESSION_SECONDS }
        ?: return null
    if (access.length !in 20..16_384 || refresh.length !in 8..16_384 ||
        access.any(Char::isWhitespace) || refresh.any(Char::isWhitespace)
    ) return null
    return PasswordRecoveryTokens(access, refresh, expiresInSeconds)
}

private fun decodeFormComponent(value: String): String? = runCatching {
    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
}.getOrNull()

private val RECOVERY_FRAGMENT_KEYS = setOf(
    "access_token",
    "refresh_token",
    "expires_at",
    "expires_in",
    "token_type",
    "type",
    // Supabase Auth currently appends this empty marker to implicit-flow redirects.
    "sb",
)

private const val MAX_RECOVERY_SESSION_SECONDS = 86_400L
