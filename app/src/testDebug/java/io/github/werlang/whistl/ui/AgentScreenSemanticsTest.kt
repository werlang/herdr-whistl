package io.github.werlang.whistl.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import io.github.werlang.whistl.R
import io.github.werlang.whistl.ui.agent.AGENT_OUTPUT_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The screen-reader semantics of the Agent screen (phase 7 r6, row 13): the terminal output is a labelled container whose
 * lines are separate text nodes, so a reader moves line by line instead of through one node with the whole buffer; and each
 * key of the key bar carries its name, in both locales.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentScreenSemanticsTest : ScreenHarness() {

    /** The container keeps its description, and its children are the lines, each a text node with its own text. */
    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun terminalLinesAreSeparateNodesInsideTheLabelledContainer() {
        showForGuard(ShotScreen.AgentWorking)
        val container = composeRule.onNodeWithTag(AGENT_OUTPUT_TAG, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals("Agent terminal", container.config.getOrNull(SemanticsProperties.ContentDescription)?.single())
        val lines = container.children.mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString("") }
        assertTrue("the output has ${lines.size} line nodes, need more than one", lines.size > 1)
        assertTrue("no line carries text", lines.any { it.isNotEmpty() })
    }

    /** Every key of the default key bar is labelled in English. */
    @Test
    @Config(qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
    fun keyBarKeysAreLabelledInEnglish() {
        showForGuard(ShotScreen.AgentWorking)
        assertKeysLabelled()
    }

    /** Every key of the default key bar is labelled in Portuguese. */
    @Test
    @Config(qualifiers = "pt-rBR-w411dp-h891dp-xxhdpi")
    fun keyBarKeysAreLabelledInPortuguese() {
        showForGuard(ShotScreen.AgentWorking)
        assertKeysLabelled()
    }

    /** Each key's label, read from the locale under test, names a node in the tree. */
    private fun assertKeysLabelled() {
        val app = RuntimeEnvironment.getApplication()
        val labels = listOf(
            R.string.key_esc,
            R.string.key_tab,
            R.string.key_ctrl,
            R.string.key_up,
            R.string.key_down,
            R.string.key_left,
            R.string.key_right,
            R.string.key_enter,
        ).map { app.getString(it) }
        labels.forEach { label ->
            val nodes = composeRule.onAllNodes(hasContentDescription(label), useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue("no key is labelled \"$label\"", nodes.isNotEmpty())
        }
    }
}
