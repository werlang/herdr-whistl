package io.github.werlang.whistl.guards

import io.github.werlang.whistl.support.Fixtures
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guard (SPEC §7, phase 4 r2 §9a): no Kotlin file under `app/src/main/java/**/ui/` passes a string literal to a user-visible
 * parameter. Lint does not check Compose literals, so this scan does.
 *
 * It checks `Text(` and the parameters `text`, `label`, `contentDescription`, `placeholder` and `title`. An empty literal is
 * allowed. A line with `// i18n-ok: <reason>` is allowed, and comment lines are not scanned. A violation names file and line.
 */
class NoHardcodedUiStringsTest {

    /** A user-visible literal: `Text("…")` or one of the named parameters given a `"…"` literal. */
    private val literal = Regex("(\\bText\\(|\\b(?:text|label|contentDescription|placeholder|title)\\s*=)\\s*\"([^\"]*)\"")

    /** No `ui` source has a user-visible string literal outside the allowed cases. */
    @Test
    fun uiSourcesPassNoStringLiteralsToUserVisibleParameters() {
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
                    literal.findAll(line)
                        .filter { it.groupValues[2].isNotEmpty() }
                        .forEach { match ->
                            val location = file.relativeTo(Fixtures.repoRoot()).path
                            violations += "$location:${index + 1}: ${match.value.trim()}"
                        }
                }
            }
        assertTrue("string literals reach the user in ui/ (use strings.xml, or // i18n-ok: <reason>):\n" + violations.joinToString("\n"), violations.isEmpty())
    }
}
