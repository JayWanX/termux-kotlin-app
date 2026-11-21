package com.termux.app.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.app.ui.compose.theme.TermuxTheme
import com.termux.shared.terminal.TerminalState

/**
 * The assistant panel.
 *
 * Rendered as a side panel rather than a full-screen sheet: the point of the assistant is to act on the
 * terminal, so the terminal has to stay visible while reading its replies. On narrow screens the panel
 * takes a fraction of the width for the same reason.
 *
 * Wrapped in [TermuxTheme] so it follows the app's dark/light setting instead of falling back to the
 * Material default, which would show a bright white sheet inside an otherwise dark app.
 */
@Composable
fun AiPanel(
    session: AgentSession,
    terminalState: TerminalState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onConfirm: (Boolean) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    onToggleSteps: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    TermuxTheme {
        val state by session.state.collectAsStateWithLifecycle()
        val listState = rememberLazyListState()
        var input by remember { mutableStateOf("") }

        // Follow the newest message as it streams in.
        LaunchedEffect(state.messages.size, state.round, state.messages.lastOrNull()?.text?.length) {
            if (state.messages.isNotEmpty()) {
                listState.animateScrollToItem(state.messages.lastIndex)
            }
        }

        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize()) {
                PanelHeader(
                    state = state,
                    terminalState = terminalState,
                    onReset = onReset,
                    onStop = onStop,
                    onClose = onClose,
                    compact = compact,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageBubble(
                            message = message,
                            compact = compact,
                            onToggleSteps = onToggleSteps,
                        )
                    }
                }

                state.pendingConfirmation?.let { summary ->
                    ConfirmationBar(summary = summary, onConfirm = onConfirm)
                }

                InputBar(
                    value = input,
                    enabled = !state.isRunning && state.pendingConfirmation == null,
                    onValueChange = { input = it },
                    onSend = {
                        val text = input.trim()
                        if (text.isNotEmpty()) {
                            input = ""
                            onSend(text)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PanelHeader(
    state: SessionState,
    terminalState: TerminalState,
    onReset: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    compact: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Assistant", style = MaterialTheme.typography.titleSmall)
            Text(
                text = buildString {
                    append(describeTerminalState(terminalState))
                    if (state.isRunning) {
                        append(" · ${state.round}/${state.maxRounds}")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.isRunning) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onStop, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("Stop", style = MaterialTheme.typography.labelSmall)
            }
        } else if (state.messages.isNotEmpty()) {
            TextButton(onClick = onReset, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text("Clear", style = MaterialTheme.typography.labelSmall)
            }
        }
        TextButton(onClick = onClose, contentPadding = PaddingValues(horizontal = 6.dp)) {
            Text(if (compact) "×" else "Close", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun MessageBubble(
    message: TurnMessage,
    compact: Boolean,
    onToggleSteps: (String) -> Unit,
) {
    val isUser = message.role == ChatMsg.ROLE_USER
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val background = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        if (message.steps.isNotEmpty()) {
            StepsSection(
                message = message,
                compact = compact,
                onToggle = { onToggleSteps(message.id) },
            )
        }
        if (message.text.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(if (isUser) 0.9f else 1f)
                    .background(background, RoundedCornerShape(8.dp))
                    .padding(horizontal = 9.dp, vertical = 7.dp),
            ) {
                if (isUser) {
                    // User input is shown verbatim; rendering it as Markdown would mangle commands.
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                } else {
                    MarkdownText(
                        markdown = message.text,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * The collapsible list of tool steps for one message.
 *
 * Collapsed by default and reduced to a single summary line. Tool steps are diagnostic detail, and a
 * dozen expanded command lines would bury the reply the user actually asked for.
 */
@Composable
private fun StepsSection(
    message: TurnMessage,
    compact: Boolean,
    onToggle: () -> Unit,
) {
    val hasError = message.steps.any { it.isError }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (message.stepsExpanded) "▾" else "▸",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = message.stepsSummary,
                style = MaterialTheme.typography.labelSmall,
                color = if (hasError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (!message.stepsExpanded && !compact) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = message.steps.lastOrNull()?.label.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (message.stepsExpanded) {
            message.steps.forEach { step -> ToolStepRow(step, compact) }
        }
    }
}

@Composable
private fun ToolStepRow(step: TurnStep, compact: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = if (step.finished) "✓" else "…",
            style = MaterialTheme.typography.bodySmall,
            color = if (step.finished) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.width(4.dp))
        Column {
            Text(
                text = step.label,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (step.detail.isNotBlank() && !compact) {
                Text(
                    text = step.detail.take(160),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun ConfirmationBar(summary: String, onConfirm: (Boolean) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.fillMaxWidth().padding(8.dp)) {
            Text(
                "Approval required",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { onConfirm(false) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("Deny", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { onConfirm(true) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text("Allow", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f).heightIn(max = 96.dp),
            enabled = enabled,
            placeholder = { Text("Ask about the terminal…", style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
        )
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onSend, enabled = enabled && value.isNotBlank()) {
            Text("Send", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Compact, human-readable rendering of a terminal state. */
fun describeTerminalState(state: TerminalState): String = when (state) {
    TerminalState.NoSession -> "no session"
    is TerminalState.Dead -> "exited" + (state.exitStatus?.let { " ($it)" } ?: "")
    is TerminalState.Busy -> "running"
    is TerminalState.Idle -> "idle"
}
