package dev.pocketherd.feature.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketherd.core.model.AgentEntry
import dev.pocketherd.core.model.AgentStatus
import dev.pocketherd.core.ui.component.StatusDot
import dev.pocketherd.domain.AgentGroup
import dev.pocketherd.domain.ConnectionState
import dev.pocketherd.domain.MachineStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(
    onOpenPane: (machineId: Long, paneId: String) -> Unit,
    onAddMachine: () -> Unit,
    viewModel: AgentsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Agents")
                        if (state.counts.total > 0) {
                            Text(
                                text = "${state.counts.blocked} blocked · " +
                                    "${state.counts.working} working · ${state.counts.total} total",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onAddMachine) {
                        Icon(Icons.Default.Add, contentDescription = "Add a machine")
                    }
                },
            )
        },
    ) { padding ->
        // Host key approval is the one thing that must interrupt: until it is answered the
        // machine cannot connect at all, and silently retrying would never resolve it.
        state.pendingHostKey?.let { pending ->
            val needs = pending.state as ConnectionState.NeedsHostKeyApproval
            HostKeyDialog(
                machineLabel = pending.machine.label,
                hostname = needs.hostname,
                fingerprint = needs.fingerprint,
                onTrust = { viewModel.approveHostKey(pending.machine.id, needs.fingerprint) },
            )
        }

        Column(Modifier.padding(padding)) {
            state.blocked.forEach { BlockedMachineCard(it) }

            // Only worth showing once there is more than one machine to choose between.
            if (state.machines.size > 1) {
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.machineFilter == null,
                        onClick = viewModel::clearMachineFilter,
                        label = { Text("All") },
                    )
                    state.machines.forEach { status ->
                        FilterChip(
                            selected = state.machineFilter == status.machine.id,
                            onClick = { viewModel.toggleMachine(status.machine.id) },
                            label = { Text(status.machine.label) },
                            leadingIcon = {
                                // A machine that is not connected still gets a chip, so
                                // filtering to it is how you find out why.
                                if (status.state !is ConnectionState.Connected) {
                                    Icon(
                                        Icons.Default.CloudOff,
                                        contentDescription = "not connected",
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }

            Row(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.groupBySpace,
                    onClick = viewModel::toggleGrouping,
                    label = { Text("Group by space") },
                )
                FilterChip(
                    selected = state.showShells,
                    onClick = viewModel::toggleShells,
                    label = { Text("Shells") },
                )
            }

            when {
                state.loading -> CenteredMessage("Connecting…")

                !state.hasMachines -> CenteredMessage(
                    "No machines yet.\nTap + to add one running herdr.",
                )

                // Never claim "connected" from an empty list alone. An empty list means
                // either nothing is running or nothing connected, and those need
                // opposite responses from the user.
                state.agents.isEmpty() -> EmptyWithMachineStates(state.machines)

                state.groupBySpace -> LazyColumn(Modifier.fillMaxSize()) {
                    state.groups.forEach { group ->
                        stickyHeader(key = group.key) { SpaceHeader(group) }
                        items(
                            group.agents,
                            key = { "${it.machineId}:${it.pane.paneId}" },
                        ) { entry ->
                            AgentRow(entry, showMachine = state.machineFilter == null) {
                                onOpenPane(entry.machineId, entry.pane.paneId)
                            }
                            HorizontalDivider()
                        }
                    }
                }

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.agents, key = { "${it.machineId}:${it.pane.paneId}" }) { entry ->
                        AgentRow(entry, showMachine = state.machineFilter == null) {
                            onOpenPane(entry.machineId, entry.pane.paneId)
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/**
 * Sticky header for one space. Opaque on purpose: a transparent header lets rows scroll
 * under the text and makes both unreadable.
 */
@Composable
private fun SpaceHeader(group: AgentGroup) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusDot(group.status)
        // Only the space label flexes. Giving the machine label a weight too made the
        // columns shift position from one header to the next.
        Text(
            text = group.spaceLabel,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = group.machineLabel,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
        Text(
            text = if (group.blockedCount > 0) {
                "${group.blockedCount} blocked"
            } else {
                "${group.agents.size}"
            },
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun AgentRow(entry: AgentEntry, showMachine: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusDot(entry.pane.agentStatus)
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.pane.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // Machine, agent, and workspace. The title above says what the agent is
                // doing; this says where it is and which CLI it is.
                text = buildString {
                    if (showMachine) append("${entry.machineLabel} · ")
                    append(entry.pane.agentLabel ?: entry.pane.paneId)
                    entry.workspace?.displayName?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = entry.pane.agentStatus.label,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** Status never relies on the dot's colour alone. */
private val AgentStatus.label: String
    get() = when (this) {
        AgentStatus.Blocked -> "blocked"
        AgentStatus.Working -> "working"
        AgentStatus.Done -> "done"
        AgentStatus.Idle -> "idle"
        AgentStatus.Unknown -> "shell"
    }

/** Lists every machine and what its connection is actually doing. */
@Composable
private fun EmptyWithMachineStates(machines: List<MachineStatus>) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val anyConnected = machines.any { it.state is ConnectionState.Connected }
        Text(
            if (anyConnected) "No agents running." else "Not connected.",
            style = MaterialTheme.typography.titleMedium,
        )
        machines.forEach { status ->
            Text(
                "${status.machine.label} — ${status.state.describe()}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun ConnectionState.describe(): String = when (this) {
    is ConnectionState.Connected -> "connected (herdr $herdrVersion, protocol $protocol)"
    is ConnectionState.Connecting -> "connecting…"
    is ConnectionState.Disconnected -> "disconnected"
    is ConnectionState.NeedsHostKeyApproval -> "waiting for host key approval"
    is ConnectionState.HostKeyChanged -> "host key changed — not connecting"
    is ConnectionState.Failed -> "failed: $reason"
}

@Composable
private fun CenteredMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Shows the fingerprint and makes the user choose. Not dismissible by tapping outside:
 * accepting a host key is a security decision, and an accidental dismissal that silently
 * leaves the machine unreachable is worse than an explicit Cancel.
 */
@Composable
private fun HostKeyDialog(
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
                    text = fingerprint,
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
        dismissButton = {
            TextButton(onClick = { dismissed = true }) { Text("Cancel") }
        },
    )
}

/** A machine that stopped for a reason retrying will not fix. */
@Composable
private fun BlockedMachineCard(status: MachineStatus) {
    val (headline, detail) = when (val s = status.state) {
        is ConnectionState.HostKeyChanged ->
            "${status.machine.label}: host key changed" to
                "Expected ${s.expected}, got ${s.actual}. The server was rebuilt, or the " +
                    "connection is being intercepted. Remove and re-add the machine only " +
                    "if you know why it changed."

        is ConnectionState.Failed -> "${status.machine.label}: not connected" to s.reason
        else -> return
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(headline, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
