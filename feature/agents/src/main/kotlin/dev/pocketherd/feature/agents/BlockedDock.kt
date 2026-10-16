package dev.pocketherd.feature.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.outlined.Workspaces
import dev.pocketherd.core.model.AgentEntry
import dev.pocketherd.domain.AgentGroup
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
    groups: List<AgentGroup>,
    now: Instant,
    filtered: Boolean,
    onOpen: (AgentEntry) -> Unit,
    selectedPaneKey: String? = null,
) {
    val blocked = groups.flatMap { it.agents }
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
                    if (filtered) "Ignores filters · by space" else "By space, oldest first",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Headers do not count against the collapsed limit; three agent rows is the
            // budget regardless of how many spaces they are spread across.
            var budget = if (expanded) Int.MAX_VALUE else COLLAPSED_ROWS
            Column(
                Modifier
                    .heightIn(max = if (expanded) 460.dp else Dp.Unspecified)
                    .then(if (expanded) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                groups.forEach { group ->
                    if (budget <= 0) return@forEach
                    val rows = group.agents.take(budget)
                    budget -= rows.size
                    SpaceDockHeader(group)
                    rows.forEach { entry ->
                        DockRow(entry, now, entry.key == selectedPaneKey) { onOpen(entry) }
                    }
                }
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

/** Which space the blocked agents below belong to. */
@Composable
private fun SpaceDockHeader(group: AgentGroup) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Outlined.Workspaces,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            group.spaceLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            group.machineLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun DockRow(
    entry: AgentEntry,
    now: Instant,
    selected: Boolean = false,
    onOpen: () -> Unit,
) {
    val minutes = entry.ageMinutes(now)
    // The space is already in the group header above, so the row spends its line on what
    // the agent is asking and which tab it is in.
    val meta = listOfNotNull(entry.prompt?.label, entry.tab?.display).joinToString(" · ")
    val spoken = minutes?.let { formatAgeSpoken(it, entry.statusSinceExact) }.orEmpty()

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .background(
                MaterialTheme.colorScheme.errorContainer,
                RoundedCornerShape(16.dp),
            )
            // An inset ring rather than a fill: a selected row is already on
            // errorContainer, and recolouring it would lose the blocked signal.
            .then(
                if (selected) {
                    Modifier.border(
                        2.dp,
                        MaterialTheme.colorScheme.onErrorContainer,
                        RoundedCornerShape(16.dp),
                    )
                } else {
                    Modifier
                },
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
