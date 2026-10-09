package earth.walkthru.aqsync

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * One language for the whole app (docs/cross-platform-guide.md, Languages and right to left).
 *
 * Android resources walk the phone's whole language list and take the first language the app
 * ships, so with French then Arabic `res/` answers in Arabic. Compose resources (the shared screens)
 * read only the first locale, `Locale.getDefault()`, and the layout direction follows that first
 * locale too, so the same phone would show English shared screens, left to right, beside Arabic
 * Android screens. [align] moves the locale Android resolved to the front of the list, for this
 * process only. It saves nothing, so an explicit per-app language (Android 13 and later) stays the
 * user's choice. That choice is already first in the list, so [align] leaves it alone.
 */
object AppLanguage {
    /**
     * The language `res/` resolved from the phone's list, read from the `locale_tag` string that
     * each `values*` folder carries.
     */
    fun resolved(context: Context): String = context.resources.getString(R.string.locale_tag)

    /**
     * [locales] with the first locale whose language is [language] moved to the front, or null when
     * it already leads or the list has no such locale (then the default English copy applies and
     * nothing mixes).
     */
    fun reorder(locales: List<Locale>, language: String): List<Locale>? {
        val index = locales.indexOfFirst { it.language == language }
        if (index <= 0) return null
        return listOf(locales[index]) + locales.filterIndexed { i, _ -> i != index }
    }

    /**
     * Sets the process default locales so Compose resources, `Locale.getDefault()` and formatters
     * follow the resolved language. Call from the Application at start and after each configuration
     * change, because Android resets the default to the phone's list on a change.
     */
    fun alignDefault(context: Context) {
        val ordered = reorder(LocaleList.getDefault().toList(), resolved(context)) ?: return
        LocaleList.setDefault(LocaleList(*ordered.toTypedArray()))
    }

    /**
     * [base] with its configuration's locales reordered, so the activity's layout direction and
     * `LocalConfiguration` follow the resolved language. Returns [base] when nothing changes.
     */
    fun wrap(base: Context): Context {
        alignDefault(base)
        val config = base.resources.configuration
        val ordered = reorder(config.locales.toList(), resolved(base)) ?: return base
        val override = Configuration(config).apply { setLocales(LocaleList(*ordered.toTypedArray())) }
        return base.createConfigurationContext(override)
    }

    private fun LocaleList.toList(): List<Locale> = List(size()) { get(it) }
}
