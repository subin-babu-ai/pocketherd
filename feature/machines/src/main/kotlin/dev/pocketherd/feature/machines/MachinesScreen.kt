package dev.pocketherd.feature.machines

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketherd.core.model.Machine
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MachinesScreen(
    onBack: () -> Unit,
    viewModel: MachinesViewModel = hiltViewModel(),
) {
    val machines by viewModel.machines.collectAsStateWithLifecycle()
    val publicKey by viewModel.publicKey.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

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
    ) { padding ->
        LazyColumn(
            Modifier
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("This device's SSH key", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Add this line to ~/.ssh/authorized_keys on every machine you " +
                                "want to reach. The private half is generated in the Android " +
                                "Keystore and cannot leave this phone.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = publicKey,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 8.dp),
                            maxLines = 3,
                        )
                        Button(
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
                            Text("Copy public key")
                        }
                    }
                }
            }

            item {
                Text(
                    if (form.isEditing) "Edit machine" else "Add a machine",
                    style = MaterialTheme.typography.titleSmall,
                )
                Field(form.label, { viewModel.update(form.copy(label = it)) }, "Label (optional)")
                Field(form.host, { viewModel.update(form.copy(host = it)) }, "Host or IP")
                Field(
                    form.port,
                    { viewModel.update(form.copy(port = it.filter(Char::isDigit))) },
                    "Port",
                )
                Field(
                    form.username,
                    { viewModel.update(form.copy(username = it)) },
                    "Username",
                )
                Field(
                    form.socketPath,
                    { viewModel.update(form.copy(socketPath = it)) },
                    "herdr socket (blank = ~/.config/herdr/herdr.sock)",
                )

                // Only machines that are not the one being edited, and that do not
                // themselves route through it, can serve as a jump host.
                JumpHostPicker(
                    machines = machines.filter { it.id != form.editingId },
                    selected = form.via,
                    onSelect = { viaId -> viewModel.update(form.copy(via = viaId)) },
                )

                if (form.isEditing) {
                    Text(
                        "Changing the host or port clears this machine's trusted key, so " +
                            "you will be asked to approve the new one.",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Button(onClick = viewModel::save, enabled = form.canSave) {
                        Text(if (form.isEditing) "Save changes" else "Add")
                    }
                    if (form.isEditing) {
                        OutlinedButton(onClick = viewModel::cancelEdit) { Text("Cancel") }
                    }
                }
            }

            items(machines, key = { it.id }) { machine ->
                HorizontalDivider()
                MachineRow(
                    machine = machine,
                    selected = form.editingId == machine.id,
                    onEdit = { viewModel.edit(machine) },
                    onDelete = { viewModel.delete(machine.id) },
                )
            }
        }
    }
}

@Composable
private fun MachineRow(
    machine: Machine,
    selected: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            // The whole row is the edit target; the pencil is there to make that discoverable.
            .clickable(onClick = onEdit)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                machine.label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                "${machine.username}@${machine.host}:${machine.port}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            machine.socketPath?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, contentDescription = "Edit ${machine.label}")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Remove ${machine.label}")
        }
    }
}

/**
 * Picks an optional jump host, the equivalent of OpenSSH's ProxyJump.
 *
 * Shown as chips rather than a dropdown because the list is short and the "Direct" case
 * should be visible as the selected default, not hidden behind a tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JumpHostPicker(
    machines: List<Machine>,
    selected: Long?,
    onSelect: (Long?) -> Unit,
) {
    if (machines.isEmpty()) return

    Text(
        "Connect via",
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text("Direct") },
        )
        machines.forEach { hop ->
            FilterChip(
                selected = selected == hop.id,
                onClick = { onSelect(hop.id) },
                label = { Text(hop.label) },
            )
        }
    }
    if (selected != null) {
        Text(
            "The hop only carries the connection. It does not need herdr running on it.",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun Field(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}
