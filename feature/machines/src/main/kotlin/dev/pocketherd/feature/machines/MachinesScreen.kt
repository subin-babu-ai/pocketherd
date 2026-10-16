package dev.pocketherd.feature.machines

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketherd.core.model.Machine
import dev.pocketherd.domain.ConnectionState
import kotlinx.coroutines.launch

/**
 * Machines as a status page rather than a form.
 *
 * The old screen led with an add/edit form, so the common case — "is my desktop
 * connected, and why not" — was answered by scrolling past a form you did not want. Now
 * each machine is a card that answers that, and adding one is a button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MachinesScreen(
    onBack: () -> Unit,
    viewModel: MachinesViewModel = hiltViewModel(),
) {
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val machines by viewModel.machines.collectAsStateWithLifecycle()
    val publicKey by viewModel.publicKey.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()

    // The form is a screen of its own, so the list never has to scroll past it.
    if (form.isOpen) {
        MachineFormScreen(
            form = form,
            others = machines.filter { it.id != form.editingId },
            onChange = viewModel::update,
            onSave = viewModel::save,
            onCancel = viewModel::cancelEdit,
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Machines") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = viewModel::addMachine,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add machine") },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // With nothing set up, the key is the first thing you need, not the last.
            if (cards.isEmpty()) {
                item { KeyCard(publicKey) }
            }

            items(cards, key = { it.machine.id }) { card ->
                MachineCardView(card) { viewModel.edit(card.machine) }
            }

            if (cards.isNotEmpty()) {
                item { KeyCard(publicKey) }
            }
        }
    }
}

@Composable
private fun MachineCardView(card: MachineCard, onOpen: () -> Unit) {
    val failed = card.state is ConnectionState.Failed ||
        card.state is ConnectionState.HostKeyChanged

    Surface(
        color = if (failed) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    if (card.machine.label.contains("book", ignoreCase = true)) {
                        Icons.Outlined.LaptopMac
                    } else {
                        Icons.Outlined.DesktopWindows
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        card.machine.label,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${card.machine.username}@${card.machine.host}:${card.machine.port}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "Edit ${card.machine.label}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ConnectionLine(card.state)

            if (!failed) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (card.blocked > 0) {
                        CountChip(Icons.Default.PanTool, "${card.blocked} blocked", urgent = true)
                    }
                    if (card.agents > 0) {
                        CountChip(Icons.Default.SmartToy, "${card.agents} agents")
                    }
                    if (card.shells > 0) {
                        CountChip(Icons.Outlined.Terminal, "${card.shells} shells")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionLine(state: ConnectionState) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (state) {
            is ConnectionState.Connected -> {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    "Connected · herdr ${state.herdrVersion} · protocol ${state.protocol}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            is ConnectionState.Connecting -> {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text("Connecting…", style = MaterialTheme.typography.bodyMedium)
            }

            is ConnectionState.NeedsHostKeyApproval -> {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    "Waiting for you to trust its host key",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // The reason is the whole point of the card for a broken machine, so it
            // wraps rather than truncating.
            is ConnectionState.HostKeyChanged -> Text(
                "Host key changed. Expected ${state.expected}, got ${state.actual}.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )

            is ConnectionState.Failed -> Text(
                state.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )

            ConnectionState.Disconnected -> Text(
                "Not connected",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CountChip(icon: ImageVector, label: String, urgent: Boolean = false) {
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
            .heightIn(min = 28.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

@Composable
private fun KeyCard(publicKey: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Outlined.VpnKey,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column {
                    Text("This phone's SSH key", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Add it to ~/.ssh/authorized_keys on each machine. The private " +
                            "half stays in the Android Keystore.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    publicKey,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(10.dp),
                )
            }
            TextButton(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(
                                android.content.ClipData.newPlainText(
                                    "pocketherd ssh key",
                                    publicKey,
                                ),
                            ),
                        )
                    }
                },
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text("  Copy key", fontWeight = FontWeight.Medium)
            }
        }
    }
}
