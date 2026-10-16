package dev.pocketherd.feature.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.InputChip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pocketherd.core.model.AgentEntry
import dev.pocketherd.core.model.AgentStatus
import dev.pocketherd.core.ui.component.StatusGlyph
import dev.pocketherd.core.ui.component.label
import dev.pocketherd.core.ui.formatAgeDisplay
import dev.pocketherd.core.ui.formatAgeSpoken
import dev.pocketherd.domain.ConnectionState
import dev.pocketherd.domain.MachineStatus
import dev.pocketherd.domain.ageMinutes
import java.time.Instant

/**
 * The answer to "does anything need me?", before any scrolling happens.
 *
 * The count leads because it is the only number that changes a decision. The subline says
 * what moved since the last visit, which is the question the user actually opened the app
 * with.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryHeader(
    state: AgentsUiState,
    onOpenMachines: () -> Unit,
    onPickMachine: (Long) -> Unit,
    onClearMachine: () -> Unit,
    onSetView: (ListView) -> Unit,
    onClearSpaceFilter: () -> Unit,
) {
    // The app draws edge to edge, so a custom top bar has to consume the status bar
    // inset itself; without this the headline renders under the clock.
    Column(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = when (val n = state.needsYouCount) {
                        0 -> "All clear"
                        1 -> "1 needs you"
                        else -> "$n need you"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
                SummarySubline(state)
            }
            IconButton(onClick = onOpenMachines) {
                Icon(Icons.Default.Dns, contentDescription = "Machines")
            }
        }

        // One scrolling row: view toggle, divider, space filter if set, machine chips.
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.height(40.dp)) {
                ListView.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = state.view == option,
                        onClick = { onSetView(option) },
                        shape = SegmentedButtonDefaults.itemShape(index, ListView.entries.size),
                    ) { Text(option.name) }
                }
            }

            VerticalDivider(
                Modifier.height(24.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )

            // A space filter is removable, so it is an InputChip rather than a FilterChip.
            state.spaceFilterLabel?.let { label ->
                InputChip(
                    selected = true,
                    onClick = onClearSpaceFilter,
                    label = { Text(label) },
                    trailingIcon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Clear space filter",
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
            }

            if (state.machines.size > 1) {
                FilterChip(
                    selected = state.machineFilter == null,
                    onClick = onClearMachine,
                    label = { Text("All ${state.totalAgents}") },
                    leadingIcon = selectedCheck(state.machineFilter == null),
                )
                state.machines.forEach { status ->
                    val count = state.countsByMachine[status.machine.id] ?: 0
                    val selected = state.machineFilter == status.machine.id
                    FilterChip(
                        selected = selected,
                        onClick = { onPickMachine(status.machine.id) },
                        label = { Text("${status.machine.label} $count") },
                        leadingIcon = when {
                            status.state !is ConnectionState.Connected -> {
                                {
                                    Icon(
                                        Icons.Default.CloudOff,
                                        contentDescription = "not connected",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                            else -> selectedCheck(selected)
                        },
                    )
                }
            }
        }
    }
}

/** M3 draws no check on a selected FilterChip unless one is supplied. */
private fun selectedCheck(selected: Boolean): (@Composable () -> Unit)? =
    if (!selected) null else {
        {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }

@Composable
private fun SummarySubline(state: AgentsUiState) {
    val unreachable = state.machines
        .filter { it.state is ConnectionState.Failed || it.state is ConnectionState.HostKeyChanged }
        .joinToString { "${it.machine.label} unreachable" }

    val since = state.since
    val base = when {
        state.lastSeenAt != null && since.any -> buildString {
            append("Since you looked at ${HH_MM.format(state.lastSeenAt)}: ")
            if (since.newlyBlocked > 0) append("${since.newlyBlocked} newly blocked")
            if (since.newlyBlocked > 0 && since.finished > 0) append(", ")
            if (since.finished > 0) append("${since.finished} finished")
        }
        state.totalAgents > 0 -> "${state.totalAgents} agents across ${state.machines.size} machines"
        else -> ""
    }

    val text = listOf(base, unreachable).filter { it.isNotBlank() }.joinToString(", ")
    if (text.isNotBlank()) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Plain section label, as used by Working. */
@Composable
fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** New carries the primary colour and the bulk action, so it gets its own shape. */
@Composable
fun NewSectionHeader(title: String, onMarkAllSeen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = 20.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        TextButton(onClick = onMarkAllSeen) { Text("Mark all seen") }
    }
}

/**
 * Quiet and Shells. Both carry a leading icon so the two collapsed groups are
 * distinguishable at a glance without reading their labels.
 */
@Composable
fun CollapsibleHeader(
    title: String,
    icon: ImageVector,
    expanded: Boolean,
    onToggle: () -> Unit,
    detail: String? = null,
    topDivider: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().padding(top = if (topDivider) 8.dp else 0.dp)) {
        // Separates the quiet half of the list from the part that still matters.
        if (topDivider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .heightIn(min = 56.dp)
                .padding(start = 20.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(22.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A row in New or Working.
 *
 * Semantics are merged and spoken as one sentence. Read field by field, a screen reader
 * would announce the glyph, the title, three metadata fragments and an age as six separate
 * nodes, which is unusable for a list meant to be skimmed.
 */
@Composable
fun AgentRow(
    entry: AgentEntry,
    now: Instant,
    showMachine: Boolean,
    modifier: Modifier = Modifier,
    titleWeight: FontWeight = FontWeight.Normal,
    /** New wraps to two lines; Working truncates to one. */
    titleLines: Int = 1,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val minutes = entry.ageMinutes(now)
    val spokenAge = minutes?.let { formatAgeSpoken(it, entry.statusSinceExact) }.orEmpty()
    val meta = entry.rowMeta(showMachine)

    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = if (titleLines > 1) 64.dp else 56.dp)
            .padding(start = 20.dp, end = if (trailing != null) 4.dp else 20.dp, top = 6.dp, bottom = 6.dp)
            .clearAndSetSemantics {
                contentDescription =
                    "${entry.pane.agentStatus.label()} $spokenAge, " +
                        "${entry.pane.displayName}, $meta"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatusGlyph(entry.pane.agentStatus, entry.unread)
        Column(Modifier.weight(1f)) {
            Text(
                entry.pane.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = titleWeight,
                maxLines = titleLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        minutes?.let {
            Text(
                formatAgeDisplay(it, entry.statusSinceExact),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFeatureSettings = "tnum",
                ),
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailing?.invoke()
    }
}

/** Compact row for Quiet and Shells. No age: nothing here is time-sensitive. */
@Composable
fun QuietRow(entry: AgentEntry, mono: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .clearAndSetSemantics {
                contentDescription = "${entry.pane.agentStatus.label()}, " +
                    "${entry.pane.displayName}, ${entry.rowMeta()}"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatusGlyph(entry.pane.agentStatus)
        Text(
            entry.pane.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = if (mono) FontFamily.Monospace else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            // The space, unless it is "~", in which case the machine says more.
            entry.workspace?.displayName?.takeIf { it != "~" } ?: entry.machineLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
        )
    }
}

@Composable
fun CenteredMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun BlockedMachineCard(status: MachineStatus) {
    val (headline, detail) = when (val s = status.state) {
        is ConnectionState.HostKeyChanged ->
            "${status.machine.label}: host key changed" to
                "Expected ${s.expected}, got ${s.actual}. The server was rebuilt, or the " +
                    "connection is being intercepted."
        is ConnectionState.Failed -> "${status.machine.label}: not connected" to s.reason
        else -> return
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(headline, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun HostKeyDialog(
    machineLabel: String,
    hostname: String,
    fingerprint: String,
    onTrust: () -> Unit,
) {
    var dismissed by remember { mutableStateOf(false) }
    if (dismissed) return

    AlertDialog(
        onDismissRequest = { },
        title = { Text("Trust $machineLabel?") },
        text = {
            Column {
                Text("First connection to $hostname. Check this fingerprint matches the host:")
                Text(
                    fingerprint,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                Text(
                    "On that machine run: ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { dismissed = true; onTrust() }) { Text("Trust") }
        },
        dismissButton = { TextButton(onClick = { dismissed = true }) { Text("Cancel") } },
    )
}

/** "desktop · r2ms-bugs › planner", dropping any part that is missing. */
fun AgentEntry.rowMeta(showMachine: Boolean = true): String = buildString {
    if (showMachine) append(machineLabel)
    workspace?.displayName?.let {
        if (isNotEmpty()) append(" · ")
        append(it)
    }
    // The tab only earns its place when it was actually named; herdr defaults the label
    // to the tab number, which says nothing.
    tab?.meaningfulLabel?.let { append(" › $it") }
}
