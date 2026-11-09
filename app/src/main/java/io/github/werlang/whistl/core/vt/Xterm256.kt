package io.github.werlang.whistl.core.vt

/**
 * The 256-colour palette of the Agent screen, as xterm defines it: the standard 16, the 6 by 6 by 6 colour cube with the levels
 * 0, 95, 135, 175, 215 and 255, and the 24 greys from 8 to 238. Programs assume these values (vim, htop, ls --color), so the screen
 * uses them whatever the engine's own table is.
 */
object Xterm256 {
    private val STANDARD: IntArray = intArrayOf(
        0x000000, 0xCD0000, 0x00CD00, 0xCDCD00, 0x0000EE, 0xCD00CD, 0x00CDCD, 0xE5E5E5,
        0x7F7F7F, 0xFF0000, 0x00FF00, 0xFFFF00, 0x5C5CFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF,
    )

    private val CUBE_LEVELS: IntArray = intArrayOf(0, 95, 135, 175, 215, 255)

    /**
     * The RGB value of palette index [index].
     *
     * @param index 0 to 255.
     * @return the colour as 0xRRGGBB.
     * @throws IllegalArgumentException when [index] is not a palette index.
     */
    fun rgb(index: Int): Int {
        require(index in 0..255) { "a palette index is 0 to 255" }
        return when {
            index < 16 -> STANDARD[index]
            index < 232 -> {
                val cube = index - 16
                val red = CUBE_LEVELS[cube / 36]
                val green = CUBE_LEVELS[(cube / 6) % 6]
                val blue = CUBE_LEVELS[cube % 6]
                (red shl 16) or (green shl 8) or blue
            }

            else -> {
                val grey = 8 + (index - 232) * 10
                (grey shl 16) or (grey shl 8) or grey
            }
        }
    }
}
