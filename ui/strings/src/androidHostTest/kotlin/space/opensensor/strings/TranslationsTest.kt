package space.opensensor.strings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every translation is complete and safe to format (docs/screens.md, Languages). Each default
 * catalogue (`values/strings.xml`) and each of its locale copies (`values-<tag>/strings.xml`) hold
 * the same keys, with the same format placeholders, and every plural carries the forms its language
 * needs. The Android language picker, the Compose modules and the iOS purpose strings list the same
 * languages.
 */
class TranslationsTest {
    /** The languages the apps ship besides English. Adding one here makes every check below cover it. */
    private val languages = listOf("ar")

    /** CLDR plural categories a language uses. Android and Compose resources choose among these. */
    private val pluralForms = mapOf("ar" to listOf("zero", "one", "two", "few", "many", "other"))

    private val placeholder = Regex("""%(\d+\$)?[-#+0,(]*\d*(\.\d+)?[a-zA-Z%]""")

    private fun placeholders(text: String): Set<String> = placeholder.findAll(text).map { it.value }.toSet()

    /** The default catalogues, Android and Compose. */
    private fun defaults(): List<File> = Catalogues.all().filter { it.parentFile?.name == "values" }

    private fun translation(default: File, language: String) = File(default.parentFile?.parentFile, "values-$language/strings.xml")

    @Test
    fun everyCatalogueHasEveryLanguage() {
        val missing = defaults().flatMap { file ->
            languages.filterNot { translation(file, it).isFile }.map { "${Catalogues.relative(file)} has no $it copy" }
        }
        assertTrue(defaults().size >= 5, "default catalogues ${defaults().map(Catalogues::relative)}")
        assertTrue(missing.isEmpty(), missing.joinToString("\n"))
    }

    @Test
    fun translationsHoldExactlyTheDefaultKeys() {
        val problems = defaults().flatMap { file ->
            val english = Catalogues.parse(file)
            languages.flatMap { language ->
                val copy = translation(file, language)
                if (!copy.isFile) return@flatMap emptyList()
                val translated = Catalogues.parse(copy)
                val englishStrings = english.keys.filterNot { '/' in it }.toSet()
                val englishPlurals = english.keys.filter { '/' in it }.map { it.substringBefore('/') }.toSet()
                val expected = englishStrings + englishPlurals.flatMap { name -> pluralForms.getValue(language).map { "$name/$it" } }
                val where = Catalogues.relative(copy)
                (expected - translated.keys).map { "$where is missing $it" } + (translated.keys - expected).map { "$where has extra $it" }
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun placeholdersMatchTheDefault() {
        val problems = defaults().flatMap { file ->
            val english = Catalogues.parse(file)
            languages.flatMap { language ->
                val copy = translation(file, language)
                if (!copy.isFile) return@flatMap emptyList()
                val where = Catalogues.relative(copy)
                Catalogues.parse(copy).mapNotNull { (key, text) ->
                    val plural = '/' in key
                    // A plural form may drop the number ("one file"), never add one. The `other` form keeps them all.
                    val source = if (plural) english["${key.substringBefore('/')}/other"] else english[key]
                    source ?: return@mapNotNull null
                    val want = placeholders(source)
                    val got = placeholders(text)
                    val ok = if (plural && !key.endsWith("/other")) want.containsAll(got) else want == got
                    if (ok) null else "$where $key has $got, English has $want"
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun theAndroidLanguagePickerListsEveryLanguage() {
        val config = File(Catalogues.root, "app/src/main/res/xml/locales_config.xml").readText()
        val listed = Regex("""android:name="([^"]+)"""").findAll(config).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("en") + languages, listed)
        val manifest = File(Catalogues.root, "app/src/main/AndroidManifest.xml").readText()
        assertTrue("android:localeConfig=\"@xml/locales_config\"" in manifest)
        assertTrue("android:supportsRtl=\"true\"" in manifest)
    }

    @Test
    fun iosPurposeStringsAreTranslated() {
        val plist = File(Catalogues.root, "ios/App/Info.plist").readText()
        val purposes = Regex("""<key>(NS\w+UsageDescription)</key><string>([^<]*)</string>""").findAll(plist)
            .associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals(setOf("NSBluetoothAlwaysUsageDescription", "NSCameraUsageDescription", "NSLocalNetworkUsageDescription", "NSLocationWhenInUseUsageDescription"), purposes.keys)
        val catalog = File(Catalogues.root, "ios/App/InfoPlist.xcstrings").readText()
        val project = File(Catalogues.root, "ios/OpenSensor.xcodeproj/project.pbxproj").readText()
        assertTrue("path = InfoPlist.xcstrings;" in project, "InfoPlist.xcstrings is not in the Xcode project")
        purposes.forEach { (key, english) ->
            for (language in listOf("en") + languages) {
                val value = translated(catalog, key, language)
                assertTrue(value != null && value.isNotBlank(), "$key has no $language text")
                if (language == "en") assertEquals(english, value, key)
                assertTrue(value.none { it in "—–;؛:" }, "$key $language breaks the copy rules")
            }
        }
        languages.forEach { assertTrue(Regex("""knownRegions = \([^)]*\b$it\b""").containsMatchIn(project), "knownRegions lacks $it") }
    }

    @Test
    fun aPurposeStringWithoutTheLanguageReadsAsMissing() {
        val catalog = """
            {"sourceLanguage":"en","version":"1.0","strings":{
              "A":{"localizations":{"en":{"stringUnit":{"state":"translated","value":"a"}}}},
              "B":{"localizations":{"ar":{"stringUnit":{"state":"translated","value":"ب"}},"en":{"stringUnit":{"state":"translated","value":"b"}}}},
              "C":{"localizations":{"ar":{"stringUnit":{"state":"needs_review","value":"ج"}}}}
            }}
        """.trimIndent()
        assertEquals(null, translated(catalog, "A", "ar"), "A must not borrow B's Arabic")
        assertEquals("ب", translated(catalog, "B", "ar"))
        assertEquals(null, translated(catalog, "C", "ar"), "only translated units count")
        assertEquals(null, translated(catalog, "D", "en"))
    }

    /** The `translated` value of [key] in [language] in an Xcode string catalog, or null. */
    private fun translated(catalog: String, key: String, language: String): String? {
        val unit = Json.parseToJsonElement(catalog).jsonObject["strings"]?.jsonObject?.get(key)?.jsonObject
            ?.get("localizations")?.jsonObject?.get(language)?.jsonObject?.get("stringUnit")?.jsonObject ?: return null
        if (unit["state"]?.jsonPrimitive?.content != "translated") return null
        return unit["value"]?.jsonPrimitive?.content
    }
}
