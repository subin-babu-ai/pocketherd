package dev.pocketherd.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PaneDisplayTest {

    @Test
    fun `the agent's window title is the headline`() {
        // Real values from herdr 0.9.0; herdr strips the status glyph for us.
        val pane = Pane(
            paneId = "w4:p9",
            agent = "claude",
            terminalTitle = "◐ Herdrm mobile app with Jetpack Compose",
            terminalTitleStripped = "Herdrm mobile app with Jetpack Compose",
        )
        assertEquals("Herdrm mobile app with Jetpack Compose", pane.displayName)
        assertEquals("claude", pane.agentLabel)
    }

    @Test
    fun `the raw title with its status glyph is never shown`() {
        val pane = Pane(
            paneId = "w3:p1",
            agent = "claude",
            terminalTitle = "✳ Delete from Slack button navigation",
            terminalTitleStripped = "Delete from Slack button navigation",
        )
        assertFalse(pane.displayName.startsWith("✳"))
    }

    @Test
    fun `falls back to the agent name when there is no title`() {
        assertEquals("codex", Pane(paneId = "w1:p1", agent = "codex").displayName)
    }

    @Test
    fun `a blank title does not win over the agent name`() {
        // An agent that cleared its title would otherwise render an empty row.
        val pane = Pane(paneId = "w1:p1", agent = "claude", terminalTitleStripped = "   ")
        assertEquals("claude", pane.displayName)
    }

    @Test
    fun `a plain shell with nothing set falls back to its id`() {
        assertEquals("w2:p3", Pane(paneId = "w2:p3").displayName)
    }
}
