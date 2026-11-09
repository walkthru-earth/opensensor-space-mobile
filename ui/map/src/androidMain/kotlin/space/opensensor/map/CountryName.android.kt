package space.opensensor.map

import java.util.IllformedLocaleException
import java.util.Locale

actual fun countryName(code: String, languageTag: String): String = try {
    Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.forLanguageTag(languageTag)).ifEmpty { code }
} catch (_: IllformedLocaleException) {
    code
}
