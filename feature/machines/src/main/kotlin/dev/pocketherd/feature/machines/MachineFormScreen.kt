package dev.pocketherd.feature.machines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.pocketherd.core.model.Machine

/**
 * Add or edit one machine, on its own screen.
 *
 * Addresses, ports and usernames are monospaced; their labels are not. Proportional type
 * makes `l`, `1` and `I` indistinguishable in a hostname, and a mistyped address is the
 * single most likely reason a machine will not connect.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MachineFormScreen(
    form: MachineForm,
    others: List<Machine>,
    onChange: (MachineForm) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (form.isEditing) "Edit ${form.label}" else "Add machine")
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Field("Label", form.label, mono = false) { onChange(form.copy(label = it)) }
            Field("Host or IP", form.host) { onChange(form.copy(host = it)) }
            Field("Port", form.port) { onChange(form.copy(port = it.filter(Char::isDigit))) }
            Field("Username", form.username) { onChange(form.copy(username = it)) }
            Field(
                label = "herdr socket",
                value = form.socketPath,
                placeholder = "~/.config/herdr/herdr.sock",
            ) { onChange(form.copy(socketPath = it)) }

            Field(
                label = "Other addresses, one per line",
                value = form.fallbackHosts,
                singleLine = false,
                supporting = "Tried in order when the first fails, so one entry works on " +
                    "any network. A tailnet address belongs here.",
            ) { onChange(form.copy(fallbackHosts = it)) }

            if (others.isNotEmpty()) {
                Text(
                    "Connect via",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = form.via == null,
                        onClick = { onChange(form.copy(via = null)) },
                        label = { Text("Direct") },
                    )
                    others.forEach { hop ->
                        FilterChip(
                            selected = form.via == hop.id,
                            onClick = { onChange(form.copy(via = hop.id)) },
                            label = { Text(hop.label) },
                        )
                    }
                }
                if (form.via != null) {
                    Text(
                        "Used only when every address above fails. The hop does not need " +
                            "herdr running on it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Only relevant when editing: a new machine has no trusted key to lose.
            if (form.isEditing) {
                Text(
                    "Changing host or port clears the trusted host key; you'll approve " +
                        "the new one on next connect.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                Modifier.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onSave,
                    enabled = form.canSave,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Save") }
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Cancel") }
            }
        }
    }
}

/**
 * Label above the field rather than floating inside it.
 *
 * M3 parks an unfocused empty field's label in the middle of the box, so "herdr socket"
 * read as a value the field already held. A label outside is always a label.
 */
@Composable
private fun Field(
    label: String,
    value: String,
    mono: Boolean = true,
    singleLine: Boolean = true,
    placeholder: String? = null,
    supporting: String? = null,
    onValueChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder?.let {
                { Text(it, fontFamily = FontFamily.Monospace) }
            },
            supportingText = supporting?.let { { Text(it) } },
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 2,
            shape = RoundedCornerShape(12.dp),
            textStyle = if (mono) LocalTextStyleMono else TextStyle.Default,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        )
    }
}

private val LocalTextStyleMono = TextStyle(fontFamily = FontFamily.Monospace)
