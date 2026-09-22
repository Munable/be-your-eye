package app.beyoureyes.monitor.feature.account

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import app.beyoureyes.monitor.R
import android.content.Context
import android.net.Uri
import java.util.Locale

/** The stable language contract shared by Android, web and service requests. */
internal enum class AppLanguage(
    val tag: String?,
    val label: Int,
) {
    SYSTEM(null, R.string.language_system),
    ENGLISH("en", R.string.language_english),
    SIMPLIFIED_CHINESE("zh-Hans", R.string.language_simplified_chinese),
    TRADITIONAL_CHINESE("zh-Hant", R.string.language_traditional_chinese),
    JAPANESE("ja", R.string.language_japanese),
    KOREAN("ko", R.string.language_korean),
    SPANISH("es", R.string.language_spanish),
    FRENCH("fr", R.string.language_french),
    GERMAN("de", R.string.language_german),
    BRAZILIAN_PORTUGUESE("pt-BR", R.string.language_brazilian_portuguese),
}

internal val supportedAppLanguages: List<AppLanguage> = AppLanguage.entries

internal fun selectedAppLanguage(): AppLanguage {
    val tag = AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag()
        ?: return AppLanguage.SYSTEM
    return AppLanguage.entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) }
        ?: AppLanguage.SYSTEM
}

internal fun setAppLanguage(language: AppLanguage) {
    val locales = language.tag?.let(LocaleListCompat::forLanguageTags)
        ?: LocaleListCompat.getEmptyLocaleList()
    AppCompatDelegate.setApplicationLocales(locales)
}

/** Stable wire locale used by the assistant, ASR, web links and notifications. */
internal fun currentAppLanguageTag(context: Context): String {
    selectedAppLanguage().tag?.let { return it }
    val locales = context.resources.configuration.locales
    for (index in 0 until locales.size()) {
        val locale = locales[index]
        when (locale.language.lowercase(Locale.ROOT)) {
            "zh" -> return when {
                locale.script.equals("Hant", ignoreCase = true) -> "zh-Hant"
                locale.script.equals("Hans", ignoreCase = true) -> "zh-Hans"
                locale.country.uppercase(Locale.ROOT) in setOf("TW", "HK", "MO") -> "zh-Hant"
                else -> "zh-Hans"
            }
            "pt" -> return "pt-BR"
            "ja", "ko", "es", "fr", "de" -> return locale.language
        }
    }
    return "en"
}

internal fun localizedWebUri(context: Context, rawUrl: String): Uri =
    Uri.parse(rawUrl).buildUpon()
        .appendQueryParameter("lang", currentAppLanguageTag(context))
        .build()

@Composable
internal fun LanguageSettingsCard(modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val selected = selectedAppLanguage()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.language_settings_title))
        Text(
            stringResource(R.string.language_settings_summary),
            color = app.beyoureyes.monitor.ProductColors.TextSecondary,
        )
        androidx.compose.foundation.layout.Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(selected.label))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                supportedAppLanguages.forEach { language ->
                    DropdownMenuItem(
                        text = { Text(stringResource(language.label)) },
                        onClick = {
                            expanded = false
                            setAppLanguage(language)
                        },
                    )
                }
            }
        }
    }
}
