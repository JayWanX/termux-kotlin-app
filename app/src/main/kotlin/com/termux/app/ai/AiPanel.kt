package com.termux.app.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.shared.terminal.TerminalState

/**
 * The assistant panel.
 *
 * Deliberately plain: a scrolling transcript, an input field and a stop button. The value is in being
 * able to see what the assistant is doing to the terminal, not in chat-app chrome.
 *
 * Rendered only when [AiAssistant.isAvailable], so the panel cannot appear for users who never enabled
 * the feature.
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
    modifier: Modifier = Modifier,
) {
    val state by session.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    // Keep the newest message visible as the turn progresses.
    LaunchedEffect(state.messages.size, state.round) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            PanelHeader(
                state = state,
                terminalState = terminalState,
                onReset = onReset,
                onStop = onStop,
                onClose = onClose,
            )
            HorizontalDivider()

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.messages, key = { it.id }) { message ->
                    MessageBubble(message)
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

@Composable
private fun PanelHeader(
    state: SessionState,
    terminalState: TerminalState,
    onReset: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Assistant", style = MaterialTheme.typography.titleSmall)
            Text(
                text = buildString {
                    append(describeTerminalState(terminalState))
                    if (state.isRunning) {
                        append(" · round ${state.round}/${state.maxRounds}")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.isRunning) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onStop) { Text("Stop") }
        } else {
            TextButton(onClick = onReset, enabled = state.messages.isNotEmpty()) { Text("Clear") }
        }
        // Without this the panel could only be dismissed from the menu that opened it.
        TextButton(onClick = onClose) { Text("Close") }
    }
}

@Composable
private fun MessageBubble(message: TurnMessage) {
    val isUser = message.role == ChatMsg.ROLE_USER
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val background = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        if (message.steps.isNotEmpty()) {
            message.steps.forEach { step -> ToolStepRow(step) }
        }
        if (message.text.isNotBlank()) {
            Box(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .background(background, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = if (isUser) FontFamily.Default else FontFamily.Default,
                )
            }
        }
    }
}

@Composable
private fun ToolStepRow(step: TurnStep) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
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
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                text = step.label,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            if (step.detail.isNotBlank()) {
                Text(
                    text = step.detail.take(200),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun ConfirmationBar(summary: String, onConfirm: (Boolean) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text("Approval required", style = MaterialTheme.typography.labelMedium)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { onConfirm(false) }) { Text("Deny") }
                TextButton(onClick = { onConfirm(true) }) { Text("Allow") }
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f).heightIn(max = 120.dp),
            enabled = enabled,
            placeholder = { Text("Ask about the terminal…") },
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onSend, enabled = enabled && value.isNotBlank()) { Text("Send") }
    }
}

/** Compact, human-readable rendering of a terminal state. */
fun describeTerminalState(state: TerminalState): String = when (state) {
    TerminalState.NoSession -> "no session"
    is TerminalState.Dead -> "exited" + (state.exitStatus?.let { " ($it)" } ?: "")
    is TerminalState.Busy -> "running"
    is TerminalState.Idle -> "idle"
}
