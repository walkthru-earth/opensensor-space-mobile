package space.opensensor.map

import platform.Foundation.NSLocale
import platform.Foundation.NSLocaleCountryCode

actual fun countryName(code: String, languageTag: String): String =
    NSLocale(localeIdentifier = languageTag).displayNameForKey(NSLocaleCountryCode, code)?.takeIf { it.isNotEmpty() } ?: code
