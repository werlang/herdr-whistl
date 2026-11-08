package io.github.werlang.whistl.guards

import io.github.werlang.whistl.support.Fixtures
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guard (SPEC §7, phase 4 r2 §9a, widened in phase 6 P0-11): no Kotlin file under `app/src/main/java/**/ui/` builds user-visible
 * text from a string literal. Lint does not check Compose literals, so this scan does.
 *
 * It flags four shapes on one line:
 * - a literal given to `Text(` or to a parameter `text`, `label`, `contentDescription`, `placeholder` or `title`;
 * - a literal used as the separator of a join, such as `joinToString(", ")`;
 * - a non-empty literal on either side of `+`, such as `"Ctrl+" + key` or `name + " · " + place`;
 * - a string template with literal text around its placeholders, such as `"$machine · $space"` or a route `"agent/$id"`.
 *
 * An empty literal is allowed. A line with `// i18n-ok: <reason>` is allowed, and comment lines are not scanned. A violation
 * names file and line.
 */
class NoHardcodedUiStringsTest {

    /** A user-visible literal: `Text("…")` or one of the named parameters given a `"…"` literal. */
    private val literal = Regex("(\\bText\\(|\\b(?:text|label|contentDescription|placeholder|title)\\s*=)\\s*\"([^\"]*)\"")

    /** A literal used as the separator of a join: `joinToString(", ")`. Empty separators join nothing visible. */
    private val joinedWithLiteral = Regex("joinToString\\s*\\(\\s*\"[^\"]+\"")

    /** A non-empty literal on one side of `+`: `"Ctrl+" + key` or `key + " · "`. */
    private val concatenated = Regex("\\+\\s*\"[^\"]+\"|\"[^\"]+\"\\s*\\+")

    /** Every string literal on a line, with its text (escapes are kept as written). */
    private val quoted = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")

    /** A `${…}` or `$name` placeholder inside a template. */
    private val placeholder = Regex("\\$\\{[^}]*}|\\$[A-Za-z_][A-Za-z_0-9]*")

    /**
     * The visible-text problems on one line.
     *
     * @param line the source line, without comment lines or `i18n-ok` lines.
     * @return the offending fragments, in the order they occur by shape; empty when the line is clean.
     */
    private fun problems(line: String): List<String> {
        val found = mutableListOf<String>()
        literal.findAll(line).filter { it.groupValues[2].isNotEmpty() }.forEach { found += it.value.trim() }
        joinedWithLiteral.findAll(line).forEach { found += it.value.trim() }
        concatenated.findAll(line).forEach { found += it.value.trim() }
        quoted.findAll(line).forEach { match ->
            val text = match.groupValues[1]
            if (text.contains('$') && placeholder.replace(text, "").isNotBlank()) found += match.value.trim()
        }
        return found
    }

    /** No `ui` source builds user-visible text from a string literal outside the allowed cases. */
    @Test
    fun uiSourcesBuildNoVisibleTextFromStringLiterals() {
        val root = File(Fixtures.repoRoot(), "app/src/main/java/io/github/werlang/whistl/ui")
        val violations = mutableListOf<String>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    val trimmed = line.trim()
                    val comment = trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")
                    if (comment || line.contains("i18n-ok:")) return@forEachIndexed
                    val location = file.relativeTo(Fixtures.repoRoot()).path
                    problems(line).forEach { violations += "$location:${index + 1}: $it" }
                }
            }
        assertTrue(
            "string literals reach the user in ui/ (use strings.xml, or // i18n-ok: <reason>):\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }
}
