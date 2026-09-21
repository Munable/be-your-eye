package app.beyoureyes.monitor.design

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/** A resource-backed or model-provided piece of UI text. */
internal sealed interface UiText {
    data class Resource(
        @param:StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : UiText

    data class Plural(
        @param:PluralsRes val id: Int,
        val quantity: Int,
        val args: List<Any> = emptyList(),
    ) : UiText

    data class Verbatim(val value: String) : UiText
}

internal fun uiText(@StringRes id: Int, vararg args: Any): UiText =
    UiText.Resource(id, args.toList())

internal fun pluralText(@PluralsRes id: Int, quantity: Int, vararg args: Any): UiText =
    UiText.Plural(id, quantity, args.toList())

@Composable
internal fun UiText.resolve(): String = when (this) {
    is UiText.Resource -> stringResource(
        id,
        *args.map { if (it is UiText) it.resolve() else it }.toTypedArray(),
    )
    is UiText.Plural -> pluralStringResource(
        id,
        quantity,
        *args.map { if (it is UiText) it.resolve() else it }.toTypedArray(),
    )
    is UiText.Verbatim -> value
}

internal fun Context.resolve(text: UiText): String = when (text) {
    is UiText.Resource -> getString(
        text.id,
        *text.args.map { if (it is UiText) resolve(it) else it }.toTypedArray(),
    )
    is UiText.Plural -> resources.getQuantityString(
        text.id,
        text.quantity,
        *text.args.map { if (it is UiText) resolve(it) else it }.toTypedArray(),
    )
    is UiText.Verbatim -> text.value
}
