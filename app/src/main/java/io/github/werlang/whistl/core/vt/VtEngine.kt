package io.github.werlang.whistl.core.vt

/**
 * The colour of one engine cell, as the engine reports it. Palette colours keep their index, so the screen resolves them from one
 * table ([Xterm256]); a 24-bit colour is already RGB.
 */
sealed interface VtColour {
    /** The terminal's default colour. */
    data object Default : VtColour

    /**
     * A palette colour: 0 to 15 are the standard colours, 16 to 231 the colour cube, 232 to 255 the greys.
     *
     * @property index the palette index.
     */
    data class Indexed(val index: Int) : VtColour

    /**
     * A 24-bit colour.
     *
     * @property value the colour as 0xRRGGBB.
     */
    data class Rgb(val value: Int) : VtColour
}

/**
 * One cell as the engine holds it, with the attributes the screen draws. A wide character takes two cells: the first holds the glyph
 * and [wide] is set; the second has [wideSpacer] set and no glyph of its own.
 *
 * @property glyph the characters of the cell; empty for a blank cell.
 * @property wide `true` for the first cell of a wide character, which takes two columns.
 * @property wideSpacer `true` for the second cell of a wide character.
 */
data class VtCell(
    val glyph: String,
    val wide: Boolean = false,
    val wideSpacer: Boolean = false,
    val fg: VtColour = VtColour.Default,
    val bg: VtColour = VtColour.Default,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val dim: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val inverse: Boolean = false,
    val conceal: Boolean = false,
)

/**
 * One line of the engine's grid: its cells, left to right. Identity matters: the screen keeps the lines it has drawn for as long as the
 * engine holds the same object, so a history line is converted once.
 *
 * @property cells the line's cells.
 */
class VtRow(val cells: List<VtCell>)

/**
 * The engine's grid at one moment: the history kept (the newest [VtEngine.snapshot] asks for), then the screen rows, and the cursor.
 *
 * @property history the history lines, oldest first.
 * @property rows the screen rows, top first.
 * @property cursorRow the cursor's row on the screen.
 * @property cursorCol the cursor's column.
 * @property cursorVisible whether the program has the cursor shown.
 */
class VtSnapshot(
    val history: List<VtRow>,
    val rows: List<VtRow>,
    val cursorRow: Int,
    val cursorCol: Int,
    val cursorVisible: Boolean,
)

/**
 * A terminal engine: bytes in, the grid out (SPEC §5.1). The emulator behind the Agent screen is one of these; the screen only sees
 * this interface, so the mapping to what is drawn runs without the engine.
 *
 * Not thread-safe: [VtScreen] serialises every call.
 */
interface VtEngine {
    /**
     * Feeds the program's bytes, in order. A sequence cut across two writes is kept until its end arrives.
     *
     * @param bytes the bytes of one DATA frame.
     */
    fun write(bytes: ByteArray)

    /**
     * Changes the grid's size. The program redraws itself after the core's own resize.
     *
     * @param cols the width in cells.
     * @param rows the height in cells.
     */
    fun resize(cols: Int, rows: Int)

    /**
     * The grid now.
     *
     * @param maxHistory the most history lines to include, the newest kept.
     * @return the snapshot.
     */
    fun snapshot(maxHistory: Int): VtSnapshot
}
