package dev.pocketherd.core.ui.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle

/**
 * Turns the output of `pane.read(format = "ansi")` into an [AnnotatedString].
 *
 * This is deliberately not a terminal emulator. `source = "visible"` returns a screen herdr
 * has already composed, so there is no cursor to track, no scroll region, and no alt
 * screen; what is left is colour and text attributes. A real VT engine would be a large
 * dependency doing work that has already happened on the host.
 *
 * ponytail: SGR only. Non-SGR escape sequences are dropped rather than interpreted. If a
 * full-screen TUI ever needs real cursor addressing, that is the point to swap in a
 * terminal emulator library, not to grow this parser.
 */
object AnsiParser {

    private const val ESC = ''
    private const val BEL = ''

    fun parse(input: String, theme: AnsiTheme = AnsiTheme.Dark): AnnotatedString {
        var style = SpanStyle()
        val text = StringBuilder()

        return buildAnnotatedString {
            fun flush() {
                if (text.isNotEmpty()) {
                    withStyle(style) { append(text.toString()) }
                    text.clear()
                }
            }

            var i = 0
            while (i < input.length) {
                val c = input[i]
                when {
                    c != ESC -> {
                        // herdr emits CRLF; the renderer only wants the newline.
                        if (c != '\r') text.append(c)
                        i++
                    }

                    // CSI: ESC [ params letter
                    i + 1 < input.length && input[i + 1] == '[' -> {
                        val end = input.indexOfFirst(i + 2) { it in '@'..'~' }
                        if (end < 0) { i = input.length; break }
                        if (input[end] == 'm') {
                            flush()
                            style = applySgr(style, input.substring(i + 2, end), theme)
                        }
                        // Any other CSI final byte is a cursor or erase op; drop it.
                        i = end + 1
                    }

                    // OSC: ESC ] ... terminated by BEL or ESC \. Carries titles and links.
                    i + 1 < input.length && input[i + 1] == ']' -> {
                        var j = i + 2
                        while (j < input.length && input[j] != BEL &&
                            !(input[j] == ESC && j + 1 < input.length && input[j + 1] == '\\')
                        ) j++
                        i = if (j < input.length && input[j] == ESC) j + 2 else j + 1
                    }

                    // Two-byte escape such as ESC(B. Skip both.
                    else -> i += 2
                }
            }
            flush()
        }
    }

    private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
        for (k in from until length) if (predicate(this[k])) return k
        return -1
    }

    /**
     * Applies one SGR sequence. An empty parameter list means reset, which is why
     * `ESC[m` and `ESC[0m` are the same thing.
     */
    private fun applySgr(current: SpanStyle, params: String, theme: AnsiTheme): SpanStyle {
        if (params.isEmpty()) return SpanStyle()
        val codes = params.split(';').map { it.toIntOrNull() ?: 0 }
        var style = current
        var i = 0
        while (i < codes.size) {
            when (val code = codes[i]) {
                0 -> style = SpanStyle()
                1 -> style = style.copy(fontWeight = FontWeight.Bold)
                2 -> style = style.copy(color = (style.color.takeOrElse(theme.foreground)).copy(alpha = 0.6f))
                3 -> style = style.copy(fontStyle = FontStyle.Italic)
                4 -> style = style.copy(textDecoration = TextDecoration.Underline)
                22 -> style = style.copy(fontWeight = FontWeight.Normal)
                23 -> style = style.copy(fontStyle = FontStyle.Normal)
                24 -> style = style.copy(textDecoration = TextDecoration.None)
                39 -> style = style.copy(color = Color.Unspecified)
                49 -> style = style.copy(background = Color.Unspecified)

                // Extended colour: 38/48 then either 5;<n> or 2;<r>;<g>;<b>.
                38, 48 -> {
                    val (color, consumed) = readExtendedColor(codes, i + 1, theme)
                    if (color != null) {
                        style = if (code == 38) style.copy(color = color)
                        else style.copy(background = color)
                    }
                    i += consumed
                }

                in 30..37 -> style = style.copy(color = theme.standard[code - 30])
                in 40..47 -> style = style.copy(background = theme.standard[code - 40])
                in 90..97 -> style = style.copy(color = theme.bright[code - 90])
                in 100..107 -> style = style.copy(background = theme.bright[code - 100])
            }
            i++
        }
        return style
    }

    /** Returns the colour and how many extra parameters it consumed. */
    private fun readExtendedColor(codes: List<Int>, start: Int, theme: AnsiTheme): Pair<Color?, Int> {
        if (start >= codes.size) return null to 0
        return when (codes[start]) {
            2 -> if (start + 3 < codes.size) {
                Color(codes[start + 1], codes[start + 2], codes[start + 3]) to 4
            } else null to (codes.size - start)

            5 -> if (start + 1 < codes.size) {
                theme.xterm256(codes[start + 1]) to 2
            } else null to (codes.size - start)

            else -> null to 1
        }
    }

    private fun Color.takeOrElse(fallback: Color): Color =
        if (this == Color.Unspecified) fallback else this
}
