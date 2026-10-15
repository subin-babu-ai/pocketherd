package dev.pocketherd.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pocketherd.core.model.AgentStatus

/**
 * The status indicator for an agent row.
 *
 * Shape carries the meaning, not colour: a filled hand in a tile for blocked, a filled
 * check for freshly done, outlines for everything quiet. Colour reinforces it and never
 * stands alone, so the list still reads correctly in greyscale or with any colour vision.
 *
 * Decorative by design. The row merges its own semantics and speaks the status in words,
 * so announcing the glyph separately would just repeat it.
 */
@Composable
fun StatusGlyph(
    status: AgentStatus,
    unread: Boolean = false,
    modifier: Modifier = Modifier,
) {
    when {
        // Blocked is the only state that needs a human, so it is the only one that gets a
        // filled tile. Everything else is an icon on the surface.
        status == AgentStatus.Blocked -> Box(
            modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.error, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.PanTool,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onError,
                modifier = Modifier.size(22.dp),
            )
        }

        status == AgentStatus.Done && unread -> Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier.size(22.dp),
        )

        status == AgentStatus.Working -> Icon(
            // Static. A spinner on a list that refreshes every few seconds implies a
            // liveness the snapshot transport does not have.
            Icons.Rounded.Autorenew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.size(22.dp),
        )

        status == AgentStatus.Done -> Icon(
            Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = modifier.size(22.dp),
        )

        status == AgentStatus.Idle -> Icon(
            Icons.Outlined.PauseCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = modifier.size(22.dp),
        )

        else -> Icon(
            Icons.Outlined.Terminal,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = modifier.size(22.dp),
        )
    }
}

/** Status in words, for the row's merged semantics and for the status column. */
fun AgentStatus.label(): String = when (this) {
    AgentStatus.Blocked -> "Blocked"
    AgentStatus.Working -> "Working"
    AgentStatus.Done -> "Done"
    AgentStatus.Idle -> "Idle"
    AgentStatus.Unknown -> "Shell"
}
