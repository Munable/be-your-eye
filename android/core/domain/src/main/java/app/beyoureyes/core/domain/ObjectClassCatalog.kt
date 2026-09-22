package app.beyoureyes.core.domain

import java.text.Normalizer
import java.util.Locale

val SUPPORTED_LOCALE_TAGS = setOf(
    "en", "zh-Hans", "zh-Hant", "ja", "ko", "es", "fr", "de", "pt-BR",
)

/** Map a platform or browser locale to the stable nine-locale Catalog contract. */
fun supportedLocaleForTag(languageTag: String): String {
    val value = languageTag.replace('_', '-')
    val lower = value.lowercase(Locale.ROOT)
    return when {
        lower.startsWith("zh") ->
            if (Regex("(?:tw|hk|mo|hant)", RegexOption.IGNORE_CASE).containsMatchIn(value)) {
                "zh-Hant"
            } else {
                "zh-Hans"
            }
        lower.startsWith("pt") -> "pt-BR"
        else -> lower.substringBefore('-').takeIf { it in SUPPORTED_LOCALE_TAGS } ?: "en"
    }
}

data class ObjectClassDefinition(
    val targetId: String,
    val labelZhCn: String,
    val labelEn: String,
    val aliases: Set<String>,
    val labels: Map<String, String> = emptyMap(),
) {
    init {
        require(targetId.matches(Regex("^[a-z0-9][a-z0-9_.-]{0,63}\$")))
        require(labelZhCn.isNotBlank() && labelZhCn.length <= 40)
        require(labelEn.isNotBlank() && labelEn.length <= 40)
        require(aliases.all(String::isNotBlank))
        require(labels.keys.all { it in SUPPORTED_LOCALE_TAGS })
        require(labels.values.all { it.isNotBlank() && it.length <= 40 })
    }

    fun localizedLabel(languageTag: String): String {
        val locale = supportedLocaleForTag(languageTag)
        return labels[locale]
            ?: if (locale == "zh-Hans" || locale == "zh-Hant") labelZhCn else labelEn
    }
}

sealed interface ObjectClassLookup {
    data class Matched(val definition: ObjectClassDefinition) : ObjectClassLookup

    data class NotFound(val normalizedInput: String) : ObjectClassLookup
}

/**
 * Exact lookup over definitions supplied by verified signed metadata.
 *
 * Natural sentences are accepted only when they contain exactly one signed name or alias. A
 * single-CJK-character alias is accepted only as the entire input because it is too ambiguous to
 * match safely inside a sentence (`车` must not turn `停车` into a car target). Shorter aliases
 * fully contained by a longer signed alias are ignored (for example `车` inside `火车`). No edit
 * distance, semantic similarity, translation or nearby-category fallback participates.
 */
class ObjectClassCatalog(definitions: Collection<ObjectClassDefinition>) {
    val definitions: List<ObjectClassDefinition> = definitions.sortedBy(ObjectClassDefinition::targetId)

    private data class AliasEntry(
        val normalized: String,
        val definition: ObjectClassDefinition,
    )

    private data class Mention(
        val start: Int,
        val endExclusive: Int,
        val definition: ObjectClassDefinition,
        val unsafeSingleCjk: Boolean,
    )

    private val aliases: List<AliasEntry> = buildList {
        this@ObjectClassCatalog.definitions.forEach { definition ->
            (definition.aliases + definition.labelZhCn + definition.labelEn + definition.labels.values).forEach { alias ->
                val normalized = normalize(alias)
                require(normalized.isNotEmpty()) { "object class alias must not be empty" }
                add(AliasEntry(normalized, definition))
            }
        }
    }

    init {
        require(this.definitions.isNotEmpty())
        require(this.definitions.map(ObjectClassDefinition::targetId).distinct().size == this.definitions.size)
        aliases.groupBy(AliasEntry::normalized).forEach { (alias, entries) ->
            require(entries.map { it.definition.targetId }.distinct().size == 1) {
                "object class alias conflict: $alias"
            }
        }
    }

    fun lookup(input: String): ObjectClassLookup {
        val normalized = normalize(input)
        if (normalized.isEmpty()) return ObjectClassLookup.NotFound(normalized)

        val exact = aliases.filter { it.normalized == normalized }
            .map(AliasEntry::definition)
            .distinctBy(ObjectClassDefinition::targetId)
        if (exact.size == 1) return ObjectClassLookup.Matched(exact.single())

        val mentions = aliases.flatMap { alias ->
            alias.normalized.occurrencesIn(normalized)
                .filter { start -> hasAsciiWordBoundaries(normalized, start, alias.normalized) }
                .map { start ->
                    Mention(
                        start = start,
                        endExclusive = start + alias.normalized.length,
                        definition = alias.definition,
                        unsafeSingleCjk = alias.normalized.isSingleCjkCharacter(),
                    )
                }
        }.distinct()
        val maximalMentions = mentions.filter { mention ->
            mentions.none { other ->
                other.start <= mention.start &&
                    other.endExclusive >= mention.endExclusive &&
                    other.endExclusive - other.start > mention.endExclusive - mention.start
            }
        }
        val matchedDefinitions = maximalMentions.map(Mention::definition)
            .distinctBy(ObjectClassDefinition::targetId)
        return if (maximalMentions.none(Mention::unsafeSingleCjk) && matchedDefinitions.size == 1) {
            ObjectClassLookup.Matched(matchedDefinitions.single())
        } else {
            ObjectClassLookup.NotFound(normalized)
        }
    }

    companion object {
        fun normalize(input: String): String = Normalizer.normalize(input, Normalizer.Form.NFKC)
            .trim()
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.ROOT)

        private fun String.occurrencesIn(text: String): Sequence<Int> = sequence {
            var start = text.indexOf(this@occurrencesIn)
            while (start >= 0) {
                yield(start)
                start = text.indexOf(this@occurrencesIn, start + 1)
            }
        }

        private fun hasAsciiWordBoundaries(text: String, start: Int, alias: String): Boolean {
            if (alias.none(::isAsciiWordCharacter)) return true
            val end = start + alias.length
            return (start == 0 || !isAsciiWordCharacter(text[start - 1])) &&
                (end == text.length || !isAsciiWordCharacter(text[end]))
        }

        private fun isAsciiWordCharacter(character: Char): Boolean =
            character in 'a'..'z' || character in '0'..'9' || character == '_'

        private fun String.isSingleCjkCharacter(): Boolean {
            if (codePointCount(0, length) != 1) return false
            return when (Character.UnicodeScript.of(codePointAt(0))) {
                Character.UnicodeScript.HAN,
                Character.UnicodeScript.HANGUL,
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA,
                -> true
                else -> false
            }
        }
    }
}
