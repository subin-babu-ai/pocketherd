package dev.pocketherd.feature.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.pocketherd.core.model.AgentEntry
import dev.pocketherd.core.ui.component.StatusGlyph
import dev.pocketherd.core.ui.formatAgeDisplay
import dev.pocketherd.core.ui.formatAgeSpoken
import dev.pocketherd.domain.ageMinutes
import java.time.Instant

private const val COLLAPSED_ROWS = 3

/**
 * The agents waiting on a human, pinned below the list.
 *
 * This is the whole redesign in one component. Blocked agents used to sit at the top of a
 * nineteen-row scroll and leave as soon as you moved; docking them means the thing you
 * opened the app for is always one thumb-reach away, in the part of the screen a thumb
 * actually reaches.
 *
 * Never filtered by machine. Hiding a blocked agent because the user was looking at a
 * different machine is precisely the failure this screen exists to prevent.
 */
@Composable
fun BlockedDock(
    blocked: List<AgentEntry>,
    now: Instant,
    filtered: Boolean,
    onOpen: (AgentEntry) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Keeps the last dock row clear of the gesture handle.
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 8.dp),
        ) {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), Alignment.Center) {
                Box(
                    Modifier
                        .size(width = 32.dp, height = 4.dp)
                        .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)),
                )
            }

            if (blocked.isEmpty()) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Default.TaskAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text("Nothing is waiting on you.", style = MaterialTheme.typography.bodyLarge)
                }
                return@Column
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Needs you · ${blocked.size}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    // While a machine filter is on, say so: the dock ignores it and that
                    // would otherwise look like a bug.
                    if (filtered) "All machines · oldest first" else "Oldest first",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val shown = if (expanded) blocked else blocked.take(COLLAPSED_ROWS)
            Column(
                Modifier
                    .heightIn(max = if (expanded) 460.dp else Dp.Unspecified)
                    .then(if (expanded) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                shown.forEach { entry -> DockRow(entry, now) { onOpen(entry) } }
            }

            if (blocked.size > COLLAPSED_ROWS) {
                Box(Modifier.fillMaxWidth(), Alignment.Center) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(
                            if (expanded) {
                                "Show fewer"
                            } else {
                                "${blocked.size - COLLAPSED_ROWS} more blocked"
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DockRow(entry: AgentEntry, now: Instant, onOpen: () -> Unit) {
    val minutes = entry.ageMinutes(now)
    val meta = buildString {
        entry.prompt?.let { append("${it.label} · ") }
        append(entry.machineLabel)
        entry.workspace?.displayName?.let { append(" · $it") }
    }
    val spoken = minutes?.let { formatAgeSpoken(it, entry.statusSinceExact) }.orEmpty()

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .background(
                MaterialTheme.colorScheme.errorContainer,
                RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onOpen)
            .heightIn(min = 72.dp)
            .padding(start = 10.dp, top = 10.dp, end = 14.dp, bottom = 10.dp)
            .clearAndSetSemantics {
                contentDescription = "Blocked $spoken, ${entry.pane.displayName}, $meta"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusGlyph(entry.pane.agentStatus)
        Column(Modifier.weight(1f)) {
            Text(
                entry.pane.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        minutes?.let {
            Text(
                formatAgeDisplay(it, entry.statusSinceExact),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                // Heavier once it has been waiting half an hour: a long wait should look
                // different from a short one without needing to read the number.
                fontWeight = if (it >= 30) FontWeight.Bold else FontWeight.Medium,
            )
        }
    }
}
