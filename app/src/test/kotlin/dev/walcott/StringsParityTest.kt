package dev.walcott

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Enforces the repo convention that every user-facing string exists in BOTH locales: a key
 * added to values/strings.xml (EN) must be added to values-es/strings.xml too, and vice
 * versa. A missing translation would otherwise only surface as a mixed-language screen on
 * whichever device happens to run in the other locale.
 */
class StringsParityTest {

    private val keyPattern = Regex("<(string|plurals) name=\"([^\"]+)\"")

    /** Deliberately untranslated keys (brand names fall back to the default locale). */
    private val untranslated = setOf("app_name")

    private fun keysOf(relativePath: String): Set<String> {
        // Gradle runs module tests with the module directory as CWD, but be tolerant of a
        // root-directory runner too.
        val file = sequenceOf(File(relativePath), File("app/$relativePath")).first { it.exists() }
        return keyPattern.findAll(file.readText()).map { it.groupValues[2] }.toSet()
    }

    @Test
    fun `every english string has a spanish translation and vice versa`() {
        val english = keysOf("src/main/res/values/strings.xml")
        val spanish = keysOf("src/main/res/values-es/strings.xml")

        val missingInSpanish = english - spanish - untranslated
        val missingInEnglish = spanish - english
        assertTrue(
            missingInSpanish.isEmpty() && missingInEnglish.isEmpty(),
            "Missing in values-es: $missingInSpanish\nMissing in values: $missingInEnglish",
        )
    }

    /**
     * The placeholders each string takes, as (position, conversion) pairs: `%1$s` and a first
     * bare `%s` both read as (1, s). `%%` is a literal percent sign, not a placeholder.
     */
    private fun placeholders(value: String): Set<Pair<Int, Char>> {
        val found = mutableSetOf<Pair<Int, Char>>()
        var implicit = 0
        for (match in Regex("%(?:(\\d+)\\$)?([sdf%])").findAll(value)) {
            val conversion = match.groupValues[2][0]
            if (conversion == '%') continue
            val position = match.groupValues[1].toIntOrNull() ?: ++implicit
            found += position to conversion
        }
        return found
    }

    private fun stringValues(relativePath: String): Map<String, String> {
        val file = sequenceOf(File(relativePath), File("app/$relativePath")).first { it.exists() }
        return Regex("<string name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** Every item of every plural, per plural name: quantity -> text. */
    private fun pluralValues(relativePath: String): Map<String, Map<String, String>> {
        val file = sequenceOf(File(relativePath), File("app/$relativePath")).first { it.exists() }
        return Regex("<plurals name=\"([^\"]+)\">(.*?)</plurals>", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .associate { plural ->
                plural.groupValues[1] to Regex("<item quantity=\"([a-z]+)\">(.*?)</item>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(plural.groupValues[2])
                    .associate { it.groupValues[1] to it.groupValues[2] }
            }
    }

    @Test
    fun `both locales take the same placeholders`() {
        // A key present in both files is not enough: a %2$d missing from one translation, or a
        // %s where the other has %d, compiles, ships, and crashes that locale the first time
        // the string is formatted — usually inside a notification, where nobody is watching.
        val english = stringValues("src/main/res/values/strings.xml")
        val spanish = stringValues("src/main/res/values-es/strings.xml")
        val mismatched = english.keys.intersect(spanish.keys)
            .filter { placeholders(english.getValue(it)) != placeholders(spanish.getValue(it)) }
        assertTrue(mismatched.isEmpty(), "Placeholders differ between locales in: $mismatched")
    }

    @Test
    fun `every plural has an other form and the same placeholders in both locales`() {
        val english = pluralValues("src/main/res/values/strings.xml")
        val spanish = pluralValues("src/main/res/values-es/strings.xml")
        val problems = mutableListOf<String>()
        for (name in english.keys.intersect(spanish.keys)) {
            val en = english.getValue(name)
            val es = spanish.getValue(name)
            // "other" is the form every language falls back to; without it a count the locale
            // has no specific form for renders as nothing at all.
            if ("other" !in en || "other" !in es) problems += "$name: no 'other'"
            val enPlaceholders = en.values.flatMap { placeholders(it) }.toSet()
            val esPlaceholders = es.values.flatMap { placeholders(it) }.toSet()
            if (enPlaceholders != esPlaceholders) problems += "$name: $enPlaceholders vs $esPlaceholders"
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun `both locales have a meaningful number of strings`() {
        // Guards against the parser silently matching nothing and the parity test
        // passing on two empty sets.
        assertTrue(keysOf("src/main/res/values/strings.xml").size > 100)
    }
}
