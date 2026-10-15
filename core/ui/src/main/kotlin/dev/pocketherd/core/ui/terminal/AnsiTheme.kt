package dev.pocketherd.core.ui.terminal

import androidx.compose.ui.graphics.Color

/**
 * Palette for the 16 named ANSI colours and the xterm-256 cube.
 *
 * Agents emit truecolor directly most of the time, so this mainly covers the older
 * sequences. The default is tuned for a dark background because that is what a terminal
 * pane looks like; [Light] exists so the pane view is readable in daylight.
 */
data class AnsiTheme(
    val foreground: Color,
    val background: Color,
    val standard: List<Color>,
    val bright: List<Color>,
) {
    /**
     * xterm-256: 0-15 are the named colours, 16-231 a 6x6x6 RGB cube, 232-255 a grey ramp.
     */
    fun xterm256(index: Int): Color = when (index) {
        in 0..7 -> standard[index]
        in 8..15 -> bright[index - 8]
        in 16..231 -> {
            val n = index - 16
            Color(cube(n / 36), cube((n / 6) % 6), cube(n % 6))
        }
        in 232..255 -> {
            val grey = 8 + (index - 232) * 10
            Color(grey, grey, grey)
        }
        else -> foreground
    }

    /** The cube is not linear: level 0 is black, then it jumps to 95 and steps by 40. */
    private fun cube(level: Int): Int = if (level == 0) 0 else 55 + level * 40

    companion object {
        val Dark = AnsiTheme(
            foreground = Color(0xFFE6EDF3),
            background = Color(0xFF0D1117),
            standard = listOf(
                Color(0xFF484F58), Color(0xFFFF7B72), Color(0xFF3FB950), Color(0xFFD29922),
                Color(0xFF58A6FF), Color(0xFFBC8CFF), Color(0xFF39C5CF), Color(0xFFB1BAC4),
            ),
            bright = listOf(
                Color(0xFF6E7681), Color(0xFFFFA198), Color(0xFF56D364), Color(0xFFE3B341),
                Color(0xFF79C0FF), Color(0xFFD2A8FF), Color(0xFF56D4DD), Color(0xFFF0F6FC),
            ),
        )

        val Light = AnsiTheme(
            foreground = Color(0xFF1F2328),
            background = Color(0xFFFFFFFF),
            standard = listOf(
                Color(0xFF24292F), Color(0xFFCF222E), Color(0xFF116329), Color(0xFF4D2D00),
                Color(0xFF0969DA), Color(0xFF8250DF), Color(0xFF1B7C83), Color(0xFF6E7781),
            ),
            bright = listOf(
                Color(0xFF57606A), Color(0xFFA40E26), Color(0xFF1A7F37), Color(0xFF633C01),
                Color(0xFF218BFF), Color(0xFFA475F9), Color(0xFF3192AA), Color(0xFF8C959F),
            ),
        )
    }
}
