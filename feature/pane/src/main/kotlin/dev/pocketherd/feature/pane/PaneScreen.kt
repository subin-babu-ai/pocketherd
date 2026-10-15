package dev.pocketherd.feature.pane

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocketherd.core.ui.component.TerminalText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaneScreen(
    onBack: () -> Unit,
    viewModel: PaneViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    var input by remember { mutableStateOf("") }
    val scroll = rememberScrollState()

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbars.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    // Follow the tail as new output arrives, the way a terminal does.
    LaunchedEffect(state.text) { scroll.animateScrollTo(scroll.maxValue) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.title.ifBlank { "Pane" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (state.subtitle.isNotBlank()) {
                            Text(
                                state.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TerminalText(
                ansiText = state.text,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scroll),
            )

            // Keys a soft keyboard has no way to produce, which is most of what an agent
            // prompt actually needs: confirm, cancel, dismiss.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("Enter" to "enter", "Esc" to "escape", "Ctrl-C" to "ctrl-c").forEach {
                    (label, keys) ->
                    AssistChip(
                        onClick = { viewModel.sendKeys(keys) },
                        label = { Text(label) },
                    )
                }
            }

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                placeholder = { Text("Reply to the agent") },
                enabled = !state.sending,
                trailingIcon = {
                    IconButton(
                        onClick = {
                            viewModel.send(input)
                            input = ""
                        },
                        enabled = input.isNotBlank() && !state.sending,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                },
            )
        }
    }
}
