package dev.pocketherd.feature.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pocketherd.core.model.AgentEntry
import dev.pocketherd.core.model.AgentStatus
import dev.pocketherd.core.ui.component.StatusGlyph
import dev.pocketherd.core.ui.formatAgeDisplay
import dev.pocketherd.domain.AgentGroup
import dev.pocketherd.domain.ageMinutes
import java.time.Instant

/**
 * Spaces as cards, each a herdr workspace with its tabs.
 *
 * The inbox answers "what needs me". This answers "what is going on", which is a different
 * question and badly served by a flat list: nineteen agents spread over nine spaces have a
 * structure the inbox deliberately flattens away.
 */
@Composable
fun SpacesView(
    spaces: List<AgentGroup>,
    now: Instant,
    onOpenPane: (Long, String) -> Unit,
    onShowInInbox: (String) -> Unit,
) {
    // Expansion is per card and remembered across recomposition, not hoisted: it is pure
    // view state and nothing outside this screen cares.
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(spaces, key = { it.key }) { group ->
            SpaceCard(
                group = group,
                now = now,
                expanded = expanded[group.key] ?: false,
                onToggle = { expanded[group.key] = !(expanded[group.key] ?: false) },
                onOpenPane = onOpenPane,
                onShowInInbox = { onShowInInbox(group.key) },
            )
        }
    }
}

@Composable
private fun SpaceCard(
    group: AgentGroup,
    now: Instant,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenPane: (Long, String) -> Unit,
    onShowInInbox: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .heightIn(min = 64.dp)
                    .padding(start = 12.dp, top = 4.dp, end = 14.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Outlined.Workspaces,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        group.spaceLabel,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        group.subtitle(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    CountChips(group)
                }
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                group.tabs.forEach { tabGroup ->
                    Text(
                        tabGroup.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
                    )
                    tabGroup.agents.forEach { entry ->
                        SpaceAgentRow(entry, now) {
                            onOpenPane(entry.machineId, entry.pane.paneId)
                        }
                    }
                }
                TextButton(
                    onClick = onShowInInbox,
                    modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                ) {
                    Icon(
                        Icons.Default.FilterList,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("  Show in inbox")
                }
            }
        }
    }
}

/** Only non-zero counts, so a quiet space stays visually quiet. */
@Composable
private fun CountChips(group: AgentGroup) {
    val chips = buildList {
        if (group.blocked > 0) {
            add(Triple(Icons.Filled.PanTool, "${group.blocked} blocked", true))
        }
        if (group.unread > 0) {
            add(Triple(Icons.Outlined.CheckCircle, "${group.unread} new", false))
        }
        if (group.working > 0) {
            add(Triple(Icons.Rounded.Autorenew, "${group.working} working", false))
        }
        if (group.shells > 0) {
            add(Triple(Icons.Outlined.Terminal, "${group.shells} shell", false))
        }
    }
    if (chips.isEmpty()) return

    Row(
        Modifier.padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        chips.forEach { (icon, label, urgent) ->
            CountChip(icon, label, urgent)
        }
    }
}

@Composable
private fun CountChip(icon: ImageVector, label: String, urgent: Boolean) {
    val bg = if (urgent) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val fg = if (urgent) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        Modifier
            .background(bg, RoundedCornerShape(8.dp))
            .heightIn(min = 24.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

@Composable
private fun SpaceAgentRow(entry: AgentEntry, now: Instant, onOpen: () -> Unit) {
    val isBlocked = entry.pane.agentStatus == AgentStatus.Blocked
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .background(
                if (isBlocked) MaterialTheme.colorScheme.errorContainer else Color.Transparent,
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onOpen)
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusGlyph(entry.pane.agentStatus, entry.unread, Modifier.size(20.dp))
        Text(
            entry.pane.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isBlocked) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            fontFamily = if (entry.pane.isAgent) null else FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        entry.ageMinutes(now)?.let {
            Text(
                formatAgeDisplay(it, entry.statusSinceExact),
                style = MaterialTheme.typography.labelMedium,
                color = if (isBlocked) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
