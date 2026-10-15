package dev.pocketherd.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketherd.core.model.AgentStatus
import dev.pocketherd.core.ui.terminal.AnsiParser
import dev.pocketherd.core.ui.terminal.AnsiTheme
import dev.pocketherd.core.ui.theme.StatusColors

@Composable
fun StatusDot(status: AgentStatus, modifier: Modifier = Modifier) {
    val color = when (status) {
        AgentStatus.Blocked -> StatusColors.Blocked
        AgentStatus.Working -> StatusColors.Working
        AgentStatus.Done -> StatusColors.Done
        AgentStatus.Idle -> StatusColors.Idle
        AgentStatus.Unknown -> StatusColors.Unknown
    }
    Box(
        modifier
            .size(10.dp)
            .background(color, CircleShape)
            // The dot repeats what the adjacent label says, so hide it from screen readers.
            .semantics { contentDescription = "" },
    )
}

/**
 * Renders a pane snapshot from `pane.read`.
 *
 * The text arrives pre-wrapped at the host's terminal width, so it is scrolled sideways
 * rather than re-wrapped. Re-wrapping would turn every box-drawing TUI into confetti.
 */
@Composable
fun TerminalText(
    ansiText: String,
    modifier: Modifier = Modifier,
    theme: AnsiTheme = AnsiTheme.Dark,
    fontSize: Int = 11,
) {
    val annotated = remember(ansiText, theme) { AnsiParser.parse(ansiText, theme) }
    Box(
        modifier
            .background(theme.background)
            .horizontalScroll(rememberScrollState()),
    ) {
        Text(
            text = annotated,
            fontFamily = FontFamily.Monospace,
            fontSize = fontSize.sp,
            lineHeight = (fontSize * 1.3).sp,
            color = theme.foreground,
            softWrap = false,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(8.dp),
        )
    }
}
