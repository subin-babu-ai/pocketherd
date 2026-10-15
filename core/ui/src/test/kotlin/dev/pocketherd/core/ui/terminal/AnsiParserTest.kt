package dev.pocketherd.core.ui.terminal

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsiParserTest {

    private val esc = '\u001B'

    @Test
    fun `plain text survives unchanged`() {
        assertEquals("hello world", AnsiParser.parse("hello world").text)
    }

    @Test
    fun `real pane-read output keeps its text and drops every escape`() {
        // Captured verbatim from pane.read(source=visible, format=ansi) on herdr 0.9.0.
        val raw = "  - Added $esc[0m$esc[38;2;177;185;249mparse_token() $esc[0m" +
            "($esc[0m$esc[38;2;177;185;249mclient.py:66$esc[0m) — detects the OAuth redirect"
        val parsed = AnsiParser.parse(raw)

        assertEquals(
            "  - Added parse_token() (client.py:66) — detects the OAuth redirect",
            parsed.text,
        )
        assertTrue("escape bytes leaked into the output", esc !in parsed.text)
    }

    @Test
    fun `truecolor foreground is applied to the right span`() {
        val parsed = AnsiParser.parse("plain${esc}[38;2;177;185;249mcolored")
        val span = parsed.spanStyles.first { it.item.color == Color(177, 185, 249) }
        assertEquals("plain".length, span.start)
        assertEquals(parsed.text.length, span.end)
    }

    @Test
    fun `reset clears styling for later text`() {
        val parsed = AnsiParser.parse("${esc}[31mred${esc}[0mplain")
        val red = parsed.spanStyles.first { it.item.color != Color.Unspecified }
        assertEquals(0, red.start)
        assertEquals("red".length, red.end)
    }

    @Test
    fun `CRLF collapses to newline so lines do not double-space`() {
        assertEquals("one\ntwo", AnsiParser.parse("one\r\ntwo").text)
    }

    @Test
    fun `non-SGR CSI sequences are dropped rather than printed`() {
        // Cursor home and erase-to-end: meaningless on an already-composed screen.
        assertEquals("text", AnsiParser.parse("${esc}[H${esc}[2Jtext").text)
    }

    @Test
    fun `OSC sequences are swallowed with both terminators`() {
        assertEquals("after", AnsiParser.parse("${esc}]0;window title\u0007after").text)
        assertEquals("after", AnsiParser.parse("${esc}]8;;https://example.com$esc\\after").text)
    }

    @Test
    fun `a truncated escape at end of input does not hang or throw`() {
        assertEquals("text", AnsiParser.parse("text${esc}[38;2;1").text)
    }

    @Test
    fun `xterm256 grey ramp and colour cube resolve to known values`() {
        val theme = AnsiTheme.Dark
        // 16 is the cube origin: pure black.
        assertEquals(Color(0, 0, 0), theme.xterm256(16))
        // 231 is the cube maximum: white at level 5 on every axis (55 + 5*40 = 255).
        assertEquals(Color(255, 255, 255), theme.xterm256(231))
        // 232 starts the grey ramp at 8.
        assertEquals(Color(8, 8, 8), theme.xterm256(232))
    }

    @Test
    fun `256-colour SGR consumes exactly its own parameters`() {
        // Bold, then 256-colour red, then italic: all three must land.
        val parsed = AnsiParser.parse("${esc}[1;38;5;9;3mstyled")
        val style = parsed.spanStyles.single().item
        assertEquals(AnsiTheme.Dark.bright[1], style.color)
        assertEquals(androidx.compose.ui.text.font.FontWeight.Bold, style.fontWeight)
        assertEquals(androidx.compose.ui.text.font.FontStyle.Italic, style.fontStyle)
    }
}
