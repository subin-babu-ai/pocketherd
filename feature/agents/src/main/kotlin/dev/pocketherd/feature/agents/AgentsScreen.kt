package dev.pocketherd.feature.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketherd.core.model.AgentEntry
import dev.pocketherd.core.model.AgentStatus
import dev.pocketherd.core.ui.component.StatusGlyph
import dev.pocketherd.core.ui.component.label
import dev.pocketherd.core.ui.formatAgeDisplay
import dev.pocketherd.core.ui.formatAgeSpoken
import dev.pocketherd.domain.ConnectionState
import dev.pocketherd.domain.MachineStatus
import dev.pocketherd.domain.ageMinutes
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val HH_MM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(
    onOpenPane: (machineId: Long, paneId: String) -> Unit,
    onOpenMachines: () -> Unit,
    viewModel: AgentsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    RecordLookedAt(viewModel)

    // One clock for the whole screen. A timer per row would wake nineteen coroutines a
    // minute to move text that changes once an hour for most of them.
    val now by produceState(initialValue = Instant.now()) {
        while (true) {
            delay(60_000)
            value = Instant.now()
        }
    }

    state.pendingHostKey?.let { pending ->
        val needs = pending.state as ConnectionState.NeedsHostKeyApproval
        HostKeyDialog(
            machineLabel = pending.machine.label,
            hostname = needs.hostname,
            fingerprint = needs.fingerprint,
            onTrust = { viewModel.approveHostKey(pending.machine.id, needs.fingerprint) },
        )
    }

    val openAndMarkSeen: (Long, String) -> Unit = { machineId, paneId ->
        viewModel.markSeen(machineId, paneId)
        onOpenPane(machineId, paneId)
    }

    Scaffold(
        topBar = {
            SummaryHeader(
                state = state,
                onOpenMachines = onOpenMachines,
                onPickMachine = viewModel::toggleMachine,
                onClearMachine = viewModel::clearMachineFilter,
                onSetView = viewModel::setView,
                onClearSpaceFilter = viewModel::clearSpaceFilter,
            )
        },
        bottomBar = {
            // Outside the list on purpose: what needs a human must not scroll away.
            BlockedDock(
                groups = state.dock,
                now = now,
                filtered = state.machineFilter != null || state.spaceFilter != null,
                onOpen = { openAndMarkSeen(it.machineId, it.pane.paneId) },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> CenteredMessage("Connecting…")
                !state.hasMachines -> CenteredMessage(
                    "No machines yet.\nAdd one running herdr to get started.",
                )
                state.view == ListView.Spaces -> SpacesView(
                    spaces = state.spaces,
                    now = now,
                    onOpenPane = openAndMarkSeen,
                    onShowInInbox = viewModel::showSpaceInInbox,
                )

                else -> TriageList(state, now, viewModel, openAndMarkSeen)
            }
        }
    }
}

@Composable
private fun TriageList(
    state: AgentsUiState,
    now: Instant,
    viewModel: AgentsViewModel,
    onOpenPane: (Long, String) -> Unit,
) {
    var quietExpanded by remember { mutableStateOf(false) }
    var shellsExpanded by remember { mutableStateOf(false) }
    val triage = state.triage

    LazyColumn(Modifier.fillMaxSize()) {
        items(state.blocked, key = { "err-${it.machine.id}" }) { BlockedMachineCard(it) }

        if (triage.new.isNotEmpty()) {
            item(key = "new-header") {
                NewSectionHeader(
                    title = state.lastSeenAt
                        ?.let { "New since ${HH_MM.format(it)} · ${triage.new.size}" }
                        ?: "New · ${triage.new.size}",
                    onMarkAllSeen = viewModel::markAllSeen,
                )
            }
            items(triage.new, key = { it.key }) { entry ->
                SwipeToMarkSeen(entry, onMarkSeen = { viewModel.markSeen(entry) }) {
                    AgentRow(
                        entry = entry,
                        now = now,
                        titleWeight = FontWeight.SemiBold,
                        titleLines = 2,
                        showMachine = state.machineFilter == null,
                        modifier = Modifier.background(MaterialTheme.colorScheme.surface),
                        trailing = {
                            IconButton(onClick = { viewModel.markSeen(entry) }) {
                                Icon(
                                    Icons.Default.DoneAll,
                                    contentDescription = "Mark ${entry.pane.displayName} seen",
                                )
                            }
                        },
                        onClick = { onOpenPane(entry.machineId, entry.pane.paneId) },
                    )
                }
            }
        }

        if (triage.working.isNotEmpty()) {
            item(key = "working-header") { SectionHeader("Working · ${triage.working.size}") }
            items(triage.working, key = { it.key }) { entry ->
                AgentRow(
                    entry = entry,
                    now = now,
                    showMachine = state.machineFilter == null,
                    onClick = { onOpenPane(entry.machineId, entry.pane.paneId) },
                )
            }
        }

        if (triage.quiet.isNotEmpty()) {
            item(key = "quiet-header") {
                CollapsibleHeader(
                    title = "Quiet · ${triage.quiet.size}",
                    icon = Icons.Outlined.Bedtime,
                    detail = "${triage.quietDoneCount} done and seen, ${triage.quietIdleCount} idle",
                    expanded = quietExpanded,
                    onToggle = { quietExpanded = !quietExpanded },
                    topDivider = true,
                )
            }
            if (quietExpanded) {
                items(triage.quiet, key = { it.key }) { entry ->
                    QuietRow(entry) { onOpenPane(entry.machineId, entry.pane.paneId) }
                }
            }
        }

        if (triage.shells.isNotEmpty()) {
            item(key = "shells-header") {
                CollapsibleHeader(
                    title = "Shells · ${triage.shells.size}",
                    icon = Icons.Outlined.Terminal,
                    expanded = shellsExpanded,
                    onToggle = { shellsExpanded = !shellsExpanded },
                )
            }
            if (shellsExpanded) {
                items(triage.shells, key = { it.key }) { entry ->
                    QuietRow(entry, mono = true) {
                        onOpenPane(entry.machineId, entry.pane.paneId)
                    }
                }
            }
        }

        if (triage.new.isEmpty() && triage.working.isEmpty() &&
            triage.quiet.isEmpty() && triage.shells.isEmpty()
        ) {
            item(key = "empty") {
                Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) {
                    Text(
                        if (state.totalAgents == 0) {
                            "No agents running."
                        } else {
                            "Nothing here for this machine."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/**
 * Marks "you have looked at the list" when it leaves the screen.
 *
 * Recorded on stop rather than start, so the summary the user came back for survives the
 * visit that is reading it. The two-second floor keeps a rotation, a notification-shade
 * pull, or an accidental launch from wiping a genuinely useful "since 13:10".
 */
@Composable
private fun RecordLookedAt(viewModel: AgentsViewModel) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        var visibleSince = 0L
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> visibleSince = SystemClock.elapsedRealtime()
                Lifecycle.Event.ON_STOP -> {
                    val shown = SystemClock.elapsedRealtime() - visibleSince
                    if (visibleSince > 0L && shown >= MIN_LOOK_MS) viewModel.recordLookedAt()
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

private const val MIN_LOOK_MS = 2_000L
