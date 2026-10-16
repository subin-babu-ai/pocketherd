package dev.pocketherd.feature.pane

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.automirrored.filled.ViewSidebar
import androidx.compose.material.icons.filled.ScheduleSend
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketherd.core.model.AgentStatus
import dev.pocketherd.core.model.PromptKind
import dev.pocketherd.core.model.PromptOption
import dev.pocketherd.core.ui.component.TerminalText
import dev.pocketherd.core.ui.formatAgeDisplay
import kotlinx.coroutines.delay
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaneScreen(
    machineId: Long,
    paneId: String,
    onBack: () -> Unit,
    onOpenPane: (Long, String) -> Unit = { _, _ -> },
    /** Shown only in the two-pane layout, where the list can be hidden. */
    onToggleList: (() -> Unit)? = null,
    listHidden: Boolean = false,
    viewModel: PaneViewModel = hiltViewModel<PaneViewModel, PaneViewModel.Factory>(
        key = "pane-$machineId-$paneId",
        creationCallback = { factory -> factory.create(machineId, paneId) },
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    val scroll = rememberScrollState()

    // Ticks only to age the snapshot chip, which is the one thing on screen that must not
    // imply the terminal is live.
    val now by produceState(initialValue = Instant.now()) {
        while (true) {
            delay(1_000)
            value = Instant.now()
        }
    }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbars.showSnackbar(it)
            viewModel.dismissError()
        }
    }
    LaunchedEffect(state.text) { scroll.animateScrollTo(scroll.maxValue) }

    Scaffold(
        topBar = { PaneHeader(state, onBack, onToggleList, listHidden) },
        snackbarHost = { SnackbarHost(snackbars) },
        bottomBar = { ReplySheet(state, viewModel, onOpenPane) },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .padding(horizontal = 12.dp)
                .fillMaxSize(),
        ) {
            TerminalText(
                ansiText = state.text,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .verticalScroll(scroll)
                    .padding(top = 44.dp),
            )
            SnapshotChip(state.snapshotAt, now, Modifier.align(Alignment.TopEnd))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaneHeader(
    state: PaneUiState,
    onBack: () -> Unit,
    onToggleList: (() -> Unit)?,
    listHidden: Boolean,
) {
    Column {
        TopAppBar(
            title = {
                Text(
                    state.title.ifBlank { "Pane" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            navigationIcon = {
                // With the list beside it there is nothing to go back to, so the slot
                // becomes the list toggle instead.
                if (onToggleList == null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            },
            actions = {
                onToggleList?.let { toggle ->
                    IconButton(onClick = toggle) {
                        Icon(
                            if (listHidden) Icons.AutoMirrored.Filled.ViewSidebar else Icons.AutoMirrored.Filled.ViewSidebar,
                            contentDescription = if (listHidden) "Show list" else "Hide list",
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )
        Column(Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp)) {
            StatusPill(state)
            if (state.meta.isNotBlank()) {
                Text(
                    state.meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusPill(state: PaneUiState) {
    val blocked = state.status == AgentStatus.Blocked
    val bg = if (blocked) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val fg = if (blocked) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    val age = state.ageMinutes?.let { formatAgeDisplay(it, state.ageExact) }
    Row(
        Modifier
            .background(bg, RoundedCornerShape(8.dp))
            .heightIn(min = 24.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            listOfNotNull(state.status.name, age).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = fg,
        )
    }
}

/** Says how stale the screen is, so nothing reads as a live terminal. */
@Composable
private fun SnapshotChip(snapshotAt: Instant?, now: Instant, modifier: Modifier = Modifier) {
    if (snapshotAt == null) return
    val seconds = ((now.toEpochMilli() - snapshotAt.toEpochMilli()) / 1000).coerceAtLeast(0)
    Row(
        modifier
            .padding(8.dp)
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
                RoundedCornerShape(14.dp),
            )
            .heightIn(min = 28.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.Default.History,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (seconds < 1) "Snapshot now" else "Snapshot ${seconds}s ago",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
