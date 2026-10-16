package dev.pocketherd.feature.pane

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.automirrored.filled.ScheduleSend
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pocketherd.core.model.AgentStatus
import dev.pocketherd.core.model.PromptKind
import dev.pocketherd.core.model.PromptOption

/**
 * Everything below the terminal: what the agent is asking, and how to answer it.
 *
 * The shape depends entirely on what the prompt parser recognised. When it recognised
 * nothing, this degrades to the single-line composer the app had before any of this, which
 * is why a mis-parse costs a tap rather than the ability to reply.
 */
@Composable
fun ReplySheet(
    state: PaneUiState,
    viewModel: PaneViewModel,
    onOpenPane: (Long, String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 14.dp, top = 12.dp, end = 14.dp, bottom = 6.dp),
        ) {
            when (val phase = state.phase) {
                is ReplyPhase.Sent -> SentState(phase.answer)
                is ReplyPhase.Resumed -> ResumedState(phase.answer, state, onOpenPane)
                ReplyPhase.Idle -> IdleReply(state, viewModel)
            }
            KeysRow(state, viewModel)
        }
    }
}

@Composable
private fun IdleReply(state: PaneUiState, viewModel: PaneViewModel) {
    var hint by remember { mutableStateOf<String?>(null) }
    val prompt = state.prompt

    // Not blocked, unrecognised, or the user chose to type: one plain text field.
    if (!state.isBlocked || state.composing || prompt == null ||
        prompt is PromptKind.Unrecognised
    ) {
        Composer(
            question = state.composerQuestion
                ?: (prompt as? PromptKind.FreeText)?.question
                ?: if (state.isBlocked) "Reply to Claude" else "Message this agent",
            multiline = state.isBlocked,
            enabled = !state.sending,
            onSend = viewModel::sendText,
        )
        return
    }

    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            questionOf(prompt),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            hint ?: "Press and hold to send",
            style = MaterialTheme.typography.bodySmall,
            color = if (hint != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }

    when (prompt) {
        is PromptKind.Menu -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            prompt.options.forEachIndexed { index, option ->
                OptionButton(
                    option = option,
                    primary = index == 0,
                    enabled = !state.sending,
                    onHint = { hint = it },
                    onSend = { viewModel.sendOption(option) },
                )
            }
        }

        is PromptKind.YesNo -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The capitalised letter in (y/N) is the default, and the default is tonal.
            listOf(
                PromptOption("y", "Yes", false) to prompt.defaultYes,
                PromptOption("n", "No", false) to !prompt.defaultYes,
            ).forEach { (option, isDefault) ->
                Box(Modifier.weight(1f)) {
                    OptionButton(
                        option = option,
                        primary = isDefault,
                        enabled = !state.sending,
                        showBadge = false,
                        onHint = { hint = it },
                        onSend = { viewModel.sendOption(option) },
                    )
                }
            }
        }

        else -> Unit
    }
}

private fun questionOf(prompt: PromptKind): String = when (prompt) {
    is PromptKind.Menu -> prompt.question.ifBlank { "Choose an option" }
    is PromptKind.YesNo -> prompt.question.ifBlank { "Confirm?" }
    is PromptKind.FreeText -> prompt.question ?: "Reply to Claude"
    PromptKind.Unrecognised -> "Reply to Claude"
}

@Composable
private fun OptionButton(
    option: PromptOption,
    primary: Boolean,
    enabled: Boolean,
    showBadge: Boolean = true,
    onHint: (String?) -> Unit,
    onSend: () -> Unit,
) {
    val container = if (primary) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val content = if (primary) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    val hold = holdToConfirm(
        key = option.key,
        fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
        onHeldLongEnough = { onHint(null); onSend() },
        onReleasedEarly = { onHint("Hold until the bar fills") },
    )

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(container, RoundedCornerShape(16.dp))
            .then(
                if (primary) {
                    Modifier
                } else {
                    Modifier.border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(16.dp),
                    )
                },
            )
            .clip(RoundedCornerShape(16.dp))
            .then(if (enabled) hold else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showBadge) {
            Box(
                Modifier
                    .size(32.dp)
                    .background(
                        if (primary) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option.key,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (primary) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Text(
            option.label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = content,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Composer(
    question: String,
    multiline: Boolean,
    enabled: Boolean,
    onSend: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    Column {
        Text(question, style = MaterialTheme.typography.titleSmall)
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                minLines = if (multiline) 3 else 1,
                maxLines = if (multiline) 6 else 1,
                shape = RoundedCornerShape(16.dp),
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            FilledIconButton(
                onClick = { onSend(text); text = "" },
                enabled = enabled && text.isNotBlank(),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.size(56.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
private fun SentState(answer: String) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ScheduleSend,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column {
            Text("Sent “$answer”", style = MaterialTheme.typography.titleSmall)
            Text(
                "Waiting for the next snapshot…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ResumedState(
    answer: String,
    state: PaneUiState,
    onOpenPane: (Long, String) -> Unit,
) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column {
                Text("Agent resumed", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Working since you answered “$answer”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val next = state.nextBlocked
        if (next != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .heightIn(min = 64.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
                    .clickable { onOpenPane(next.machineId, next.pane.paneId) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Next blocked · ${state.blockedRemaining} left",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Text(
                        next.pane.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        maxLines = 1,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/**
 * Keys a soft keyboard cannot produce.
 *
 * Ctrl-C needs a hold rather than a tap: it interrupts whatever the agent is doing, and
 * sits next to two controls that are taps.
 */
@Composable
private fun KeysRow(state: PaneUiState, viewModel: PaneViewModel) {
    var hint by remember { mutableStateOf<String?>(null) }

    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TextButton(onClick = { viewModel.sendKeys("escape") }) { Text("Esc", fontFamily = FontFamily.Monospace) }
        TextButton(onClick = { viewModel.sendKeys("enter") }) { Text("Enter", fontFamily = FontFamily.Monospace) }

        Box(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .then(
                    holdToConfirm(
                        key = "ctrl-c",
                        fillColor = MaterialTheme.colorScheme.error.copy(alpha = 0.25f),
                        onHeldLongEnough = { hint = null; viewModel.sendKeys("ctrl-c") },
                        onReleasedEarly = { hint = "Hold Ctrl-C" },
                    ),
                )
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            Text(
                "Ctrl-C",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // Only offered when there is something to switch away from.
        if (state.isBlocked && state.prompt is PromptKind.Menu) {
            TextButton(onClick = viewModel::toggleComposing) {
                Text(if (state.composing) "Options" else "Type")
            }
        }

        Box(Modifier.weight(1f))
        hint?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
