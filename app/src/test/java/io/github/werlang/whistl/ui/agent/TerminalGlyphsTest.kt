package io.github.werlang.whistl.ui.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The geometric glyphs of the terminal (0.3.1): block elements and box drawing are drawn as shapes, because the font
 * does not cover them at one cell of advance — opencode's banner came out as thick misaligned boxes.
 */
class TerminalGlyphsTest {

    /** A full block fills the whole cell. */
    @Test
    fun aFullBlockFillsTheCell() {
        val glyph = requireNotNull(TerminalGlyphs.glyphFor('\u2588'))

        assertEquals(1, glyph.filled.size)
        val part = glyph.filled.single()
        assertEquals(0f, part.left, 0.001f)
        assertEquals(0f, part.top, 0.001f)
        assertEquals(1f, part.right, 0.001f)
        assertEquals(1f, part.bottom, 0.001f)
        assertTrue(glyph.strokes.isEmpty())
    }

    /** The halves fill the half they name. */
    @Test
    fun theHalvesFillTheirOwnHalfOfTheCell() {
        val upper = requireNotNull(TerminalGlyphs.glyphFor('\u2580')).filled.single()
        assertEquals(0f, upper.top, 0.001f)
        assertEquals(0.5f, upper.bottom, 0.001f)

        val lower = requireNotNull(TerminalGlyphs.glyphFor('\u2584')).filled.single()
        assertEquals(0.5f, lower.top, 0.001f)
        assertEquals(1f, lower.bottom, 0.001f)

        val left = requireNotNull(TerminalGlyphs.glyphFor('\u258C')).filled.single()
        assertEquals(0f, left.left, 0.001f)
        assertEquals(0.5f, left.right, 0.001f)
    }

    /** A line glyph is a stroke through the middle of the cell, not a fill. */
    @Test
    fun aHorizontalLineIsOneStrokeThroughTheMiddle() {
        val glyph = requireNotNull(TerminalGlyphs.glyphFor('\u2500'))

        assertTrue(glyph.filled.isEmpty())
        val line = glyph.strokes.single()
        assertEquals(0f, line.fromX, 0.001f)
        assertEquals(1f, line.toX, 0.001f)
        assertEquals(0.5f, line.fromY, 0.001f)
        assertEquals(0.5f, line.toY, 0.001f)
        assertEquals(false, line.double)
    }

    /** A corner is two strokes meeting at the centre: one out, one down. */
    @Test
    fun aCornerIsTwoStrokesFromTheCentre() {
        val glyph = requireNotNull(TerminalGlyphs.glyphFor('\u250C'))

        assertEquals(2, glyph.strokes.size)
        assertTrue(glyph.strokes.all { it.fromX == 0.5f && it.fromY == 0.5f })
        assertTrue(glyph.strokes.any { it.toX == 1f && it.toY == 0.5f })
        assertTrue(glyph.strokes.any { it.toX == 0.5f && it.toY == 1f })
    }

    /** The double lines stay double. */
    @Test
    fun aDoubleLineIsMarkedDouble() {
        assertTrue(requireNotNull(TerminalGlyphs.glyphFor('\u2551')).strokes.all { it.double })
        assertTrue(requireNotNull(TerminalGlyphs.glyphFor('\u2550')).strokes.all { it.double })
    }

    /** Text is still text: the font draws it. */
    @Test
    fun ordinaryTextIsNotAGeometricGlyph() {
        assertNull(TerminalGlyphs.glyphFor('a'))
        assertNull(TerminalGlyphs.glyphFor(' '))
        assertNull(TerminalGlyphs.glyphFor('\u00E9'))
    }

    /** The shades keep the alpha that distinguishes them. */
    @Test
    fun theShadesCarryTheirAlpha() {
        assertEquals(0.25f, requireNotNull(TerminalGlyphs.shadeAlphaFor('\u2591')), 0.001f)
        assertEquals(0.5f, requireNotNull(TerminalGlyphs.shadeAlphaFor('\u2592')), 0.001f)
        assertEquals(0.75f, requireNotNull(TerminalGlyphs.shadeAlphaFor('\u2593')), 0.001f)
        assertNull(TerminalGlyphs.shadeAlphaFor('\u2588'))
    }

    /** Lower blocks fill exactly the eighths their names count: three eighths is not a quarter. */
    @Test
    fun theLowerBlocksFillTheirEighths() {
        fun top(ch: Char) = requireNotNull(TerminalGlyphs.glyphFor(ch)).filled.single().top

        assertEquals(0.875f, top('\u2581'), 0.001f) // one eighth
        assertEquals(0.75f, top('\u2582'), 0.001f)  // two eighths
        assertEquals(0.625f, top('\u2583'), 0.001f) // three eighths
        assertEquals(0.375f, top('\u2585'), 0.001f) // five eighths
        assertEquals(0.125f, top('\u2587'), 0.001f) // seven eighths
    }

    /** The quadrants fill the corners their Unicode names name, no more and no less. */
    @Test
    fun theQuadrantsFillTheCornersTheirNamesSay() {
        fun corners(ch: Char) = requireNotNull(TerminalGlyphs.glyphFor(ch)).filled
            .map { "${it.left}/${it.top}" }
            .sorted()

        assertEquals(listOf("0.0/0.0", "0.0/0.5", "0.5/0.5"), corners('\u2599')) // all but upper right
        assertEquals(3, requireNotNull(TerminalGlyphs.glyphFor('\u259B')).filled.size) // all but lower right
        assertEquals(3, requireNotNull(TerminalGlyphs.glyphFor('\u259C')).filled.size) // all but lower left
        assertEquals(2, requireNotNull(TerminalGlyphs.glyphFor('\u259E')).filled.size) // upper right, lower left
        assertEquals(3, requireNotNull(TerminalGlyphs.glyphFor('\u259F')).filled.size) // all but upper left
    }

    /** A heavy box line carries its weight, so the renderer can thicken it. */
    @Test
    fun aHeavyLineIsMarkedHeavy() {
        assertTrue(requireNotNull(TerminalGlyphs.glyphFor('\u2501')).strokes.all { it.heavy })
        assertTrue(requireNotNull(TerminalGlyphs.glyphFor('\u2503')).strokes.all { it.heavy })
        assertTrue(requireNotNull(TerminalGlyphs.glyphFor('\u250F')).strokes.all { it.heavy })
        assertTrue(requireNotNull(TerminalGlyphs.glyphFor('\u2500')).strokes.none { it.heavy })
    }

    /** The double dashes are two short segments, not one solid rule. */
    @Test
    fun theDoubleDashesAreTwoSegments() {
        listOf('\u254C', '\u254D', '\u254E', '\u254F').forEach { ch ->
            val glyph = requireNotNull(TerminalGlyphs.glyphFor(ch)) { "no geometry for $ch" }
            assertEquals("$ch", 2, glyph.filled.size)
            assertTrue("$ch", glyph.strokes.isEmpty())
        }
    }

    /** The rounded corners no longer fall through to the font. */
    @Test
    fun theRoundedCornersAreMapped() {
        listOf('\u256D', '\u256E', '\u256F', '\u2570').forEach { ch ->
            val glyph = requireNotNull(TerminalGlyphs.glyphFor(ch)) { "no geometry for $ch" }
            assertEquals(2, glyph.strokes.size)
        }
    }
}
