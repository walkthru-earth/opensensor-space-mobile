package space.opensensor.model

/**
 * The owner site of a Thing, as the board keeps it in its owner record (firmware #148). Each value is
 * one of [SiteField]. Null means the board did not report it. Empty means it is unset. Nothing here is
 * a default, the board's own text is kept as it came.
 */
data class ThingSite(
    val title: String? = null,
    val provider: String? = null,
    val url: String? = null,
    val description: String? = null,
    val deploymentId: String? = null,
) {
    operator fun get(field: SiteField): String? = when (field) {
        SiteField.TITLE -> title
        SiteField.PROVIDER -> provider
        SiteField.URL -> url
        SiteField.DESCRIPTION -> description
        SiteField.DEPLOYMENT_ID -> deploymentId
    }

    fun with(field: SiteField, value: String): ThingSite = when (field) {
        SiteField.TITLE -> copy(title = value)
        SiteField.PROVIDER -> copy(provider = value)
        SiteField.URL -> copy(url = value)
        SiteField.DESCRIPTION -> copy(description = value)
        SiteField.DEPLOYMENT_ID -> copy(deploymentId = value)
    }

    /** The first problem of each field that has one. A null or empty field has none. */
    fun problems(): Map<SiteField, SiteProblem> =
        SiteField.entries.mapNotNull { f -> SiteRules.check(f, this[f].orEmpty())?.let { f to it } }.toMap()

    /**
     * The fields whose value differs from [stored], with this value. An emptied field is included as
     * empty, which clears it on the board. A field that stays empty is left out.
     */
    fun changesFrom(stored: ThingSite): Map<SiteField, String> =
        SiteField.entries.mapNotNull { f -> this[f].orEmpty().takeIf { it != stored[f].orEmpty() }?.let { f to it } }.toMap()
}

/** One owner site value and its cap in UTF-8 bytes, from the firmware `SET_CONFIG` table. */
enum class SiteField(val maxBytes: Int) {
    TITLE(64),
    PROVIDER(64),
    URL(96),
    DESCRIPTION(160),
    DEPLOYMENT_ID(32),
}

/** Why a site value is refused. Each one is a firmware rejection the phone catches before sending. */
enum class SiteProblem {
    /** More UTF-8 bytes than [SiteField.maxBytes]. */
    TOO_LONG,

    /** A C0 control, DEL or a C1 control. */
    CONTROL,

    /** Text that is not valid UTF-8, such as a lone surrogate. */
    ENCODING,

    /** A link that does not start with `https://` and a letter or digit. */
    SCHEME,

    /** A link whose host part holds an `@`. */
    USER_INFO,

    /** A character outside the allowed set of the field. */
    CHARSET,

    /** A deployment id with a `-` at an end, or the literal `unknown`. */
    RESERVED,
}

/**
 * The owner site value rules of `opensensor-space-firmware` `check_owner_value`, mirrored so the
 * phone refuses a value the board would refuse with `ERROR` 12. Caps are UTF-8 bytes. An empty
 * value always passes, because it clears the key.
 */
object SiteRules {
    private const val SCHEME = "https://"
    private const val URL_MARKS = "-._~:/?#[]@!$&'()*+,;=%"
    private const val UNSET_DEPLOYMENT = "unknown"

    fun check(field: SiteField, value: String): SiteProblem? {
        if (hasLoneSurrogate(value)) return SiteProblem.ENCODING
        if (value.encodeToByteArray().size > field.maxBytes) return SiteProblem.TOO_LONG
        if (value.isEmpty()) return null
        return when (field) {
            SiteField.URL -> checkUrl(value)
            SiteField.DEPLOYMENT_ID -> checkDeployment(value)
            SiteField.TITLE, SiteField.PROVIDER, SiteField.DESCRIPTION -> checkText(value)
        }
    }

    /** No C0 (below U+0020), DEL (U+007F) or C1 (U+0080 to U+009F). */
    private fun checkText(value: String): SiteProblem? =
        if (value.any { isControl(it) || it in '\u0080'..'\u009f' }) SiteProblem.CONTROL else null

    private fun checkUrl(value: String): SiteProblem? {
        if (value.any { isControl(it) }) return SiteProblem.CONTROL
        if (!value.startsWith(SCHEME) || value.length == SCHEME.length || !asciiAlnum(value[SCHEME.length])) return SiteProblem.SCHEME
        val rest = value.substring(SCHEME.length)
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        if ('@' in rest.substring(0, authorityEnd)) return SiteProblem.USER_INFO
        if (!value.all { asciiAlnum(it) || it in URL_MARKS }) return SiteProblem.CHARSET
        return null
    }

    private fun checkDeployment(value: String): SiteProblem? {
        if (!value.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }) return SiteProblem.CHARSET
        if (value.first() == '-' || value.last() == '-' || value == UNSET_DEPLOYMENT) return SiteProblem.RESERVED
        return null
    }

    private fun isControl(c: Char) = c < ' ' || c == '\u007f'

    private fun asciiAlnum(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    private fun hasLoneSurrogate(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c.isHighSurrogate() -> {
                    if (i + 1 >= value.length || !value[i + 1].isLowSurrogate()) return true
                    i += 2
                }
                c.isLowSurrogate() -> return true
                else -> i++
            }
        }
        return false
    }
}
