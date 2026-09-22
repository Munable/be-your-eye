package app.beyoureyes.monitor

import android.content.Context

/** Public, signed metadata shipped with the candidate; artifacts still require explicit download. */
internal object CommunityModelMetadata {
    const val CATALOG_URL = "https://github.com/Munable/be-your-eye/releases/download/models-v1/catalog.json"
    fun read(context: Context, url: String): ByteArray? {
        if (!BuildConfig.COMMUNITY_BUILD || BuildConfig.MODEL_CATALOG_URL != CATALOG_URL) return null
        val prefix = CATALOG_URL.removeSuffix("catalog.json")
        if (!url.startsWith(prefix)) return null
        val name = url.removePrefix(prefix)
        val relative = when {
            name == "catalog.json" -> name
            name.matches(Regex("[a-z0-9_]+\\.manifest\\.json")) -> "manifests/" + name.removeSuffix(".manifest.json") + ".json"
            else -> return null
        }
        if (!relative.matches(Regex("(?:catalog|manifests/[a-z0-9_]+)\\.json"))) return null
        return runCatching { context.assets.open("community-models/$relative").use { it.readBytes() } }.getOrNull()
    }
}
