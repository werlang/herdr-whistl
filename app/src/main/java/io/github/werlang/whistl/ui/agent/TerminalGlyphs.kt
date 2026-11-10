package io.github.werlang.whistl.ui.agent

/**
 * The geometry of the terminal glyphs that must not be drawn by the font: block elements (`█ ▀ ▄ ▌ ▐` and the
 * shades) and box-drawing lines (`─ │ ┌ ┐ ╔ ╗`). A monospace font may not cover them at all, and Android's
 * fallback draws them with advances that do not match one cell, which is how a TUI logo turns into thick
 * misaligned boxes (0.3.1, opencode's banner). They are drawn as shapes instead, so one cell is one cell.
 *
 * Pure Kotlin: coordinates are fractions of the cell, `(0,0)` top-left to `(1,1)` bottom-right. A heavy line
 * (U+2501, U+2503, U+250F..) carries [Stroke.heavy] so the renderer can thicken it.
 */
internal object TerminalGlyphs {

    /** A filled part of a cell, in cell fractions. */
    internal data class Filled(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** A line inside a cell, in cell fractions. `double` draws it as two parallel strokes, `heavy` as a thicker one. */
    internal data class Stroke(
        val fromX: Float,
        val fromY: Float,
        val toX: Float,
        val toY: Float,
        val double: Boolean = false,
        val heavy: Boolean = false,
    )

    /** What a cell holds when its character is drawn geometrically: filled parts and strokes. */
    internal data class Glyph(val filled: List<Filled> = emptyList(), val strokes: List<Stroke> = emptyList())

    private fun fill(left: Float, top: Float, right: Float, bottom: Float) = Filled(left, top, right, bottom)
    private fun stroke(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        double: Boolean = false,
        heavy: Boolean = false,
    ) = Stroke(fromX, fromY, toX, toY, double, heavy)

    private const val MID = 0.5f
    private const val QUARTER = 0.25f
    private const val EIGHTH = 0.125f

    /** The quadrants, as fills. Named so the block table reads like the Unicode names. */
    private fun quadrants(vararg corners: Int): List<Filled> = corners.map { corner ->
        when (corner) {
            0 -> fill(0f, 0f, MID, MID)          // upper left
            1 -> fill(MID, 0f, 1f, MID)          // upper right
            2 -> fill(0f, MID, MID, 1f)          // lower left
            else -> fill(MID, MID, 1f, 1f)       // lower right
        }
    }

    private fun horizontal(double: Boolean = false, heavy: Boolean = false) = stroke(0f, MID, 1f, MID, double, heavy)
    private fun vertical(double: Boolean = false, heavy: Boolean = false) = stroke(MID, 0f, MID, 1f, double, heavy)
    private fun toRight(double: Boolean = false, heavy: Boolean = false) = stroke(MID, MID, 1f, MID, double, heavy)
    private fun toLeft(double: Boolean = false, heavy: Boolean = false) = stroke(0f, MID, MID, MID, double, heavy)
    private fun toDown(double: Boolean = false, heavy: Boolean = false) = stroke(MID, MID, MID, 1f, double, heavy)
    private fun toUp(double: Boolean = false, heavy: Boolean = false) = stroke(MID, 0f, MID, MID, double, heavy)
    private fun rounded(vararg strokes: Stroke) = Glyph(strokes = strokes.toList())

    /**
     * The shapes of [ch], or `null` when the character is text and the font must draw it.
     *
     * Block elements cover U+2580..U+259F and box drawing covers U+2500..U+257F: light, heavy, double, rounded
     * corners and the half-line dashes all map to a shape, so a border never leaves a hole.
     */
    internal fun glyphFor(ch: Char): Glyph? = when (ch) {
        // ---- block elements -------------------------------------------------
        '\u2588' -> Glyph(filled = listOf(fill(0f, 0f, 1f, 1f)))                     // full block
        '\u2580' -> Glyph(filled = listOf(fill(0f, 0f, 1f, MID)))                     // upper half
        '\u2584' -> Glyph(filled = listOf(fill(0f, MID, 1f, 1f)))                     // lower half
        '\u258C' -> Glyph(filled = listOf(fill(0f, 0f, MID, 1f)))                     // left half
        '\u2590' -> Glyph(filled = listOf(fill(MID, 0f, 1f, 1f)))                     // right half
        // Lower blocks, one to seven eighths: U+2581 is one eighth, U+2587 is seven.
        '\u2581' -> Glyph(filled = listOf(fill(0f, 1f - EIGHTH, 1f, 1f)))
        '\u2582' -> Glyph(filled = listOf(fill(0f, 1f - 2 * EIGHTH, 1f, 1f)))
        '\u2583' -> Glyph(filled = listOf(fill(0f, 1f - 3 * EIGHTH, 1f, 1f)))
        '\u2585' -> Glyph(filled = listOf(fill(0f, 1f - 5 * EIGHTH, 1f, 1f)))
        '\u2586' -> Glyph(filled = listOf(fill(0f, 1f - 6 * EIGHTH, 1f, 1f)))
        '\u2587' -> Glyph(filled = listOf(fill(0f, 1f - 7 * EIGHTH, 1f, 1f)))
        // Side blocks.
        '\u2589' -> Glyph(filled = listOf(fill(0f, 0f, 1f - EIGHTH, 1f)))             // seven eighths left
        '\u258A' -> Glyph(filled = listOf(fill(0f, 0f, 1f - 2 * EIGHTH, 1f)))
        '\u258B' -> Glyph(filled = listOf(fill(0f, 0f, 1f - 3 * EIGHTH, 1f)))
        '\u258D' -> Glyph(filled = listOf(fill(0f, 0f, 3 * EIGHTH, 1f)))              // three eighths left
        '\u258E' -> Glyph(filled = listOf(fill(0f, 0f, 2 * EIGHTH, 1f)))
        '\u258F' -> Glyph(filled = listOf(fill(0f, 0f, EIGHTH, 1f)))
        '\u2594' -> Glyph(filled = listOf(fill(0f, 0f, 1f, EIGHTH)))                  // upper one eighth
        '\u2595' -> Glyph(filled = listOf(fill(1f - EIGHTH, 0f, 1f, 1f)))             // right one eighth
        // Shades: one fill, drawn with the alpha the caller takes from shadeAlphaFor.
        '\u2591', '\u2592', '\u2593' -> Glyph(filled = listOf(fill(0f, 0f, 1f, 1f)))
        // Quadrants, by their Unicode names.
        '\u2596' -> Glyph(filled = quadrants(2))
        '\u2597' -> Glyph(filled = quadrants(3))
        '\u2598' -> Glyph(filled = quadrants(0))
        '\u259D' -> Glyph(filled = quadrants(1))
        '\u2599' -> Glyph(filled = quadrants(0, 2, 3))
        '\u259A' -> Glyph(filled = quadrants(0, 3))
        '\u259B' -> Glyph(filled = quadrants(0, 1, 2))
        '\u259C' -> Glyph(filled = quadrants(0, 1, 3))
        '\u259E' -> Glyph(filled = quadrants(1, 2))
        '\u259F' -> Glyph(filled = quadrants(1, 2, 3))

        // ---- box drawing, light --------------------------------------------
        '\u2500' -> Glyph(strokes = listOf(horizontal()))
        '\u2501' -> Glyph(strokes = listOf(horizontal(heavy = true)))
        '\u2502' -> Glyph(strokes = listOf(vertical()))
        '\u2503' -> Glyph(strokes = listOf(vertical(heavy = true)))
        '\u2504' -> Glyph(strokes = listOf(stroke(0f, MID, 3 * QUARTER, MID)))
        '\u2505' -> Glyph(strokes = listOf(stroke(0f, MID, 3 * QUARTER, MID, heavy = true)))
        '\u2506' -> Glyph(strokes = listOf(stroke(MID, 0f, MID, 3 * QUARTER)))
        '\u2507' -> Glyph(strokes = listOf(stroke(MID, 0f, MID, 3 * QUARTER, heavy = true)))
        '\u2508' -> Glyph(strokes = listOf(stroke(QUARTER, MID, 3 * QUARTER, MID)))
        '\u2509' -> Glyph(strokes = listOf(stroke(QUARTER, MID, 3 * QUARTER, MID, heavy = true)))
        '\u250A' -> Glyph(strokes = listOf(stroke(MID, QUARTER, MID, 3 * QUARTER)))
        '\u250B' -> Glyph(strokes = listOf(stroke(MID, QUARTER, MID, 3 * QUARTER, heavy = true)))
        '\u250C' -> Glyph(strokes = listOf(toRight(), toDown()))
        '\u250D' -> Glyph(strokes = listOf(toRight(heavy = true), toDown()))
        '\u250E' -> Glyph(strokes = listOf(toRight(), toDown(heavy = true)))
        '\u250F' -> Glyph(strokes = listOf(toRight(heavy = true), toDown(heavy = true)))
        '\u2510' -> Glyph(strokes = listOf(toLeft(), toDown()))
        '\u2511' -> Glyph(strokes = listOf(toLeft(heavy = true), toDown()))
        '\u2512' -> Glyph(strokes = listOf(toLeft(), toDown(heavy = true)))
        '\u2513' -> Glyph(strokes = listOf(toLeft(heavy = true), toDown(heavy = true)))
        '\u2514' -> Glyph(strokes = listOf(toRight(), toUp()))
        '\u2515' -> Glyph(strokes = listOf(toRight(heavy = true), toUp()))
        '\u2516' -> Glyph(strokes = listOf(toRight(), toUp(heavy = true)))
        '\u2517' -> Glyph(strokes = listOf(toRight(heavy = true), toUp(heavy = true)))
        '\u2518' -> Glyph(strokes = listOf(toLeft(), toUp()))
        '\u2519' -> Glyph(strokes = listOf(toLeft(heavy = true), toUp()))
        '\u251A' -> Glyph(strokes = listOf(toLeft(), toUp(heavy = true)))
        '\u251B' -> Glyph(strokes = listOf(toLeft(heavy = true), toUp(heavy = true)))
        '\u251C' -> Glyph(strokes = listOf(vertical(), toRight()))
        '\u251D' -> Glyph(strokes = listOf(vertical(), toRight(heavy = true)))
        '\u251E' -> Glyph(strokes = listOf(vertical(heavy = true), toRight()))
        '\u251F' -> Glyph(strokes = listOf(vertical(), toRight(heavy = true)))
        '\u2520' -> Glyph(strokes = listOf(vertical(heavy = true), toRight()))
        '\u2521' -> Glyph(strokes = listOf(vertical(heavy = true), toRight(heavy = true)))
        '\u2522' -> Glyph(strokes = listOf(vertical(), toRight(heavy = true)))
        '\u2523' -> Glyph(strokes = listOf(vertical(heavy = true), toRight(heavy = true)))
        '\u2524' -> Glyph(strokes = listOf(vertical(), toLeft()))
        '\u2525' -> Glyph(strokes = listOf(vertical(), toLeft(heavy = true)))
        '\u2526' -> Glyph(strokes = listOf(vertical(heavy = true), toLeft()))
        '\u2527' -> Glyph(strokes = listOf(vertical(), toLeft(heavy = true)))
        '\u2528' -> Glyph(strokes = listOf(vertical(heavy = true), toLeft()))
        '\u2529' -> Glyph(strokes = listOf(vertical(heavy = true), toLeft(heavy = true)))
        '\u252A' -> Glyph(strokes = listOf(vertical(), toLeft(heavy = true)))
        '\u252B' -> Glyph(strokes = listOf(vertical(heavy = true), toLeft(heavy = true)))
        '\u252C' -> Glyph(strokes = listOf(horizontal(), toDown()))
        '\u252D' -> Glyph(strokes = listOf(horizontal(), toDown(heavy = true)))
        '\u252E' -> Glyph(strokes = listOf(horizontal(heavy = true), toDown()))
        '\u252F' -> Glyph(strokes = listOf(horizontal(), toDown(heavy = true)))
        '\u2530' -> Glyph(strokes = listOf(horizontal(), toDown(heavy = true)))
        '\u2531' -> Glyph(strokes = listOf(horizontal(heavy = true), toDown(heavy = true)))
        '\u2532' -> Glyph(strokes = listOf(horizontal(heavy = true), toDown()))
        '\u2533' -> Glyph(strokes = listOf(horizontal(heavy = true), toDown(heavy = true)))
        '\u2534' -> Glyph(strokes = listOf(horizontal(), toUp()))
        '\u2535' -> Glyph(strokes = listOf(horizontal(), toUp(heavy = true)))
        '\u2536' -> Glyph(strokes = listOf(horizontal(heavy = true), toUp()))
        '\u2537' -> Glyph(strokes = listOf(horizontal(), toUp(heavy = true)))
        '\u2538' -> Glyph(strokes = listOf(horizontal(), toUp(heavy = true)))
        '\u2539' -> Glyph(strokes = listOf(horizontal(heavy = true), toUp(heavy = true)))
        '\u253A' -> Glyph(strokes = listOf(horizontal(heavy = true), toUp()))
        '\u253B' -> Glyph(strokes = listOf(horizontal(heavy = true), toUp(heavy = true)))
        '\u253C' -> Glyph(strokes = listOf(horizontal(), vertical()))
        '\u253D' -> Glyph(strokes = listOf(horizontal(), vertical(), toRight(heavy = true)))
        '\u253E' -> Glyph(strokes = listOf(horizontal(), vertical(), toLeft(heavy = true)))
        '\u253F' -> Glyph(strokes = listOf(horizontal(), vertical(heavy = true)))
        '\u2540' -> Glyph(strokes = listOf(horizontal(), vertical(), toRight(heavy = true)))
        '\u2541' -> Glyph(strokes = listOf(horizontal(), vertical(), toLeft(heavy = true)))
        '\u2542' -> Glyph(strokes = listOf(horizontal(), vertical(heavy = true)))
        '\u2543', '\u2544', '\u2545', '\u2546' -> Glyph(strokes = listOf(horizontal(), vertical(), toRight(heavy = true)))
        '\u2547', '\u2548', '\u2549', '\u254A' -> Glyph(strokes = listOf(horizontal(), vertical(), toLeft(heavy = true)))
        '\u254B' -> Glyph(strokes = listOf(horizontal(heavy = true), vertical(heavy = true)))
        // Double dashes: two short segments instead of one line, so they read as a dash and not as a solid rule.
        '\u254C' -> Glyph(filled = listOf(fill(0f, 0.46f, 0.4f, 0.54f), fill(0.6f, 0.46f, 1f, 0.54f)))
        '\u254D' -> Glyph(filled = listOf(fill(0f, 0.40f, 0.4f, 0.60f), fill(0.6f, 0.40f, 1f, 0.60f)))
        '\u254E' -> Glyph(filled = listOf(fill(0.46f, 0.15f, 0.54f, 0.40f), fill(0.46f, 0.60f, 0.54f, 0.85f)))
        '\u254F' -> Glyph(filled = listOf(fill(0.40f, 0.15f, 0.60f, 0.40f), fill(0.40f, 0.60f, 0.60f, 0.85f)))

        // ---- box drawing, double -------------------------------------------
        '\u2550' -> Glyph(strokes = listOf(horizontal(double = true)))
        '\u2551' -> Glyph(strokes = listOf(vertical(double = true)))
        '\u2552' -> Glyph(strokes = listOf(toRight(double = true), toDown()))
        '\u2553' -> Glyph(strokes = listOf(toRight(), toDown(double = true)))
        '\u2554' -> Glyph(strokes = listOf(toRight(double = true), toDown(double = true)))
        '\u2555' -> Glyph(strokes = listOf(toLeft(double = true), toDown()))
        '\u2556' -> Glyph(strokes = listOf(toLeft(), toDown(double = true)))
        '\u2557' -> Glyph(strokes = listOf(toLeft(double = true), toDown(double = true)))
        '\u2558' -> Glyph(strokes = listOf(toRight(double = true), toUp()))
        '\u2559' -> Glyph(strokes = listOf(toRight(), toUp(double = true)))
        '\u255A' -> Glyph(strokes = listOf(toRight(double = true), toUp(double = true)))
        '\u255B' -> Glyph(strokes = listOf(toLeft(double = true), toUp()))
        '\u255C' -> Glyph(strokes = listOf(toLeft(), toUp(double = true)))
        '\u255D' -> Glyph(strokes = listOf(toLeft(double = true), toUp(double = true)))
        '\u255E' -> Glyph(strokes = listOf(vertical(double = true), toRight()))
        '\u255F' -> Glyph(strokes = listOf(vertical(), toRight(double = true)))
        '\u2560' -> Glyph(strokes = listOf(vertical(double = true), toRight(double = true)))
        '\u2561' -> Glyph(strokes = listOf(vertical(double = true), toLeft()))
        '\u2562' -> Glyph(strokes = listOf(vertical(), toLeft(double = true)))
        '\u2563' -> Glyph(strokes = listOf(vertical(double = true), toLeft(double = true)))
        '\u2564' -> Glyph(strokes = listOf(horizontal(double = true), toDown()))
        '\u2565' -> Glyph(strokes = listOf(horizontal(), toDown(double = true)))
        '\u2566' -> Glyph(strokes = listOf(horizontal(double = true), toDown(double = true)))
        '\u2567' -> Glyph(strokes = listOf(horizontal(double = true), toUp()))
        '\u2568' -> Glyph(strokes = listOf(horizontal(), toUp(double = true)))
        '\u2569' -> Glyph(strokes = listOf(horizontal(double = true), toUp(double = true)))
        '\u256A' -> Glyph(strokes = listOf(horizontal(double = true), vertical(double = true)))
        '\u256B' -> Glyph(strokes = listOf(horizontal(), vertical(double = true)))
        '\u256C' -> Glyph(strokes = listOf(horizontal(double = true), vertical(double = true)))

        // ---- box drawing, rounded corners ----------------------------------
        '\u256D' -> rounded(toRight(), toDown())
        '\u256E' -> rounded(toLeft(), toDown())
        '\u256F' -> rounded(toLeft(), toUp())
        '\u2570' -> rounded(toRight(), toUp())

        // ---- diagonals and dashes (U+2571..U+2573, U+2574..U+257F) ---------
        '\u2571' -> Glyph(strokes = listOf(stroke(1f, 0f, 0f, 1f)))
        '\u2572' -> Glyph(strokes = listOf(stroke(0f, 0f, 1f, 1f)))
        '\u2573' -> Glyph(strokes = listOf(stroke(0f, 0f, 1f, 1f), stroke(1f, 0f, 0f, 1f)))
        '\u2574' -> Glyph(strokes = listOf(toLeft()))
        '\u2575' -> Glyph(strokes = listOf(toUp()))
        '\u2576' -> Glyph(strokes = listOf(toRight()))
        '\u2577' -> Glyph(strokes = listOf(toDown()))
        '\u2578' -> Glyph(strokes = listOf(toLeft(heavy = true)))
        '\u2579' -> Glyph(strokes = listOf(toUp(heavy = true)))
        '\u257A' -> Glyph(strokes = listOf(toRight(heavy = true)))
        '\u257B' -> Glyph(strokes = listOf(toDown(heavy = true)))
        '\u257C' -> Glyph(strokes = listOf(toLeft(), toRight(heavy = true)))
        '\u257D' -> Glyph(strokes = listOf(toUp(), toDown(heavy = true)))
        '\u257E' -> Glyph(strokes = listOf(toLeft(heavy = true), toRight()))
        '\u257F' -> Glyph(strokes = listOf(toUp(heavy = true), toDown()))

        else -> null
    }

    /** The alpha a shade block (U+2591..U+2593) is drawn with; `null` when the character is not a shade. */
    internal fun shadeAlphaFor(ch: Char): Float? = when (ch) {
        '\u2591' -> 0.25f
        '\u2592' -> 0.5f
        '\u2593' -> 0.75f
        else -> null
    }
}
