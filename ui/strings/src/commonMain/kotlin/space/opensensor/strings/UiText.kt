package space.opensensor.strings

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A message that state code holds without choosing words. State keeps a [UiText], and the
 * composable edge turns it into the shown string with [resolve], so the English copy lives in
 * Compose resources only.
 */
sealed interface UiText {
    /** No message. Resolves to an empty string. */
    data object None : UiText

    /**
     * Words a platform adapter already chose, kept as they arrive. Native iOS adapters still send
     * their own explanations (Bluetooth state, export, Wi-Fi access) until they report typed outcomes.
     */
    data class Plain(val text: String) : UiText

    /** A catalogue string with its format arguments (`%1$s`, `%1$d`). */
    data class Resource(val res: StringResource, val args: List<Any> = emptyList()) : UiText

    /** A catalogue plural chosen by [count], with its format arguments. */
    data class Plural(val res: PluralStringResource, val count: Int, val args: List<Any> = emptyList()) : UiText

    /** True when nothing would be shown. */
    val isEmpty: Boolean get() = this is None || (this is Plain && text.isEmpty())

    companion object {
        /** A catalogue string with format arguments. */
        fun of(res: StringResource, vararg args: Any): UiText = Resource(res, args.toList())

        /** A catalogue plural for [count] with format arguments. */
        fun plural(res: PluralStringResource, count: Int, vararg args: Any): UiText = Plural(res, count, args.toList())

        /** Adapter words, or [None] when they are blank. */
        fun plain(text: String): UiText = if (text.isEmpty()) None else Plain(text)
    }
}

/** The string to show, read from the catalogue for [UiText.Resource] and [UiText.Plural]. */
@Composable
fun UiText.resolve(): String = when (this) {
    UiText.None -> ""
    is UiText.Plain -> text
    is UiText.Resource -> stringResource(res, *args.toTypedArray())
    is UiText.Plural -> pluralStringResource(res, count, *args.toTypedArray())
}
