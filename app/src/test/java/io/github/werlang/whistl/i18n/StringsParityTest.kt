package io.github.werlang.whistl.i18n

import io.github.werlang.whistl.support.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The English and Portuguese resource files hold the same keys, with the same positional arguments (SPEC §7, phase 4 r2 §8).
 * A `<plurals>` has the same name and quantities in both, and each item has the same arguments as the other language's item
 * for that quantity. Every argument is positional: a bare `%s` or `%d` is refused.
 */
class StringsParityTest {

    /** A file's resources: strings by name, and plurals by name with one argument signature per quantity. */
    private class Resources(
        val strings: Map<String, String>,
        val plurals: Map<String, Map<String, String>>,
    )

    private fun load(path: String): Resources {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(Fixtures.repoRoot(), path))
        val root = document.documentElement
        val strings = root.children("string").associate { it.getAttribute("name") to it.textContent }
        val plurals = root.children("plurals").associate { plural ->
            plural.getAttribute("name") to plural.children("item").associate { it.getAttribute("quantity") to it.textContent }
        }
        return Resources(strings, plurals)
    }

    private fun org.w3c.dom.Element.children(tag: String): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.filter { it.tagName == tag }

    /** The positional arguments of a text, as `index:type` in order; fails on a non-positional `%`. */
    private fun signature(text: String, where: String): List<String> {
        val bare = Regex("%(?!\\d+\\$[sd]|%)").find(text)
        assertTrue("$where: a bare % (positional arguments only): $text", bare == null)
        return Regex("%(\\d+)\\$([sd])").findAll(text).map { "${it.groupValues[1]}:${it.groupValues[2]}" }.toList()
    }

    /** The two resource files have the same string and plural names, and the same arguments for each. */
    @Test
    fun englishAndPortugueseHaveTheSameKeysAndArguments() {
        val english = load("app/src/main/res/values/strings.xml")
        val portuguese = load("app/src/main/res/values-pt-rBR/strings.xml")

        assertEquals("string names", english.strings.keys.sorted(), portuguese.strings.keys.sorted())
        assertEquals("plural names", english.plurals.keys.sorted(), portuguese.plurals.keys.sorted())
        english.strings.forEach { (name, text) ->
            assertEquals("arguments of $name", signature(text, "en $name"), signature(portuguese.strings.getValue(name), "pt-BR $name"))
        }
        english.plurals.forEach { (name, items) ->
            val other = portuguese.plurals.getValue(name)
            // Each language has its own plural categories (Portuguese adds "many"), so only the shared ones are compared.
            assertTrue("$name: both define 'other'", "other" in items && "other" in other)
            items.filterKeys { it in other }.forEach { (quantity, text) ->
                assertEquals(
                    "arguments of $name ($quantity)",
                    signature(text, "en $name"),
                    signature(other.getValue(quantity), "pt-BR $name"),
                )
            }
        }
    }
}
