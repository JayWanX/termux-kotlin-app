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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.termux.app.ui.compose.theme.TermuxTheme
import com.termux.shared.terminal.TerminalState

/** Travel that counts as a deliberate dismiss swipe inside the panel. */
private val DISMISS_SWIPE_THRESHOLD = 48.dp

/**
 * The assistant panel.
 *
 * A plain function of [SessionState]: the panel owns no conversation of its own, so the same state can
 * be rendered in a test without a live model or a terminal.
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
    state: SessionState,
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
    TermuxTheme(darkTheme = assistantDarkTheme()) {
        val listState = rememberLazyListState()
        // Saveable so a rotation or activity recreation does not discard a half-typed question.
        var input by rememberSaveable { mutableStateOf("") }
        val dismissThreshold = with(LocalDensity.current) { DISMISS_SWIPE_THRESHOLD.toPx() }
        var dismissDrag by remember { mutableFloatStateOf(0f) }

        // Follow the newest message as it streams in.
        LaunchedEffect(state.messages.size, state.round, state.messages.lastOrNull()?.text?.length) {
            if (state.messages.isNotEmpty()) {
                listState.animateScrollToItem(state.messages.lastIndex)
            }
        }

        Surface(
            modifier = modifier.fillMaxSize(),
            // A slight rounding and elevation make the panel read as a sheet sliding over the terminal
            // rather than as a second window fighting it for the screen.
            shape = RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp),
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize()) {
                // Everything above the input dismisses the panel on a rightward swipe. The input bar and
                // the confirmation bar are left out so that moving the cursor in the text field, or
                // tapping Allow, can never be mistaken for the dismiss gesture.
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .pointerDismiss(
                            thresholdPx = dismissThreshold,
                            onDrag = { dismissDrag += it },
                            onEnd = {
                                val shouldClose = EdgeSwipe.shouldClose(dismissDrag, dismissThreshold)
                                dismissDrag = 0f
                                if (shouldClose) onClose()
                            },
                            onCancel = { dismissDrag = 0f },
                        ),
                ) {
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

/**
 * Adds the rightward dismiss gesture.
 *
 * [detectHorizontalDragGestures] only claims the pointer once it has moved horizontally past the touch
 * slop, so taps and vertical scrolling inside the panel still reach the children underneath.
 */
private fun Modifier.pointerDismiss(
    thresholdPx: Float,
    onDrag: (Float) -> Unit,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
): Modifier = this.pointerInput(thresholdPx) {
    detectHorizontalDragGestures(
        onDragEnd = { onEnd() },
        onDragCancel = { onCancel() },
        onHorizontalDrag = { _, delta -> onDrag(delta) },
    )
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
            .padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (state.isRunning) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            HeaderIconButton(
                icon = { Icon(Icons.Filled.Stop, contentDescription = "Stop") },
                onClick = onStop,
            )
        } else if (state.messages.isNotEmpty()) {
            HeaderIconButton(
                icon = { Icon(Icons.Filled.Delete, contentDescription = "Clear conversation") },
                onClick = onReset,
            )
        }
        HeaderIconButton(
            icon = { Icon(Icons.Filled.Close, contentDescription = "Close panel") },
            onClick = onClose,
        )
    }
}

/** A compact, consistently sized icon button for the panel header. */
@Composable
private fun HeaderIconButton(icon: @Composable () -> Unit, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(30.dp),
    ) {
        Box(Modifier.size(17.dp), contentAlignment = Alignment.Center) { icon() }
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
                        codeBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        codeColor = MaterialTheme.colorScheme.onSurface,
                        linkColor = MaterialTheme.colorScheme.primary,
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
 *
 * The summary is a tinted chip rather than a line of text: it has to read as one tappable object, and
 * an error has to be visible without expanding anything. The expanded rows then sit on a quieter
 * surface with a coloured status dot, so the shape of the transcript (reply, then detail) stays clear.
 */
@Composable
private fun StepsSection(
    message: TurnMessage,
    compact: Boolean,
    onToggle: () -> Unit,
) {
    val hasError = message.steps.any { it.isError }
    val accent = if (hasError) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(7.dp))
                .background(
                    if (hasError) {
                        // Tinted rather than saturated: the summary still has to read as one row among
                        // the replies, and a full-strength error container turned it into a banner.
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    },
                )
                .clickable(onClick = onToggle)
                .padding(horizontal = 7.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (message.stepsExpanded) {
                    Icons.Filled.ExpandLess
                } else {
                    Icons.Filled.ExpandMore
                },
                contentDescription = if (message.stepsExpanded) {
                    "Hide tool steps"
                } else {
                    "Show tool steps"
                },
                tint = accent,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(5.dp))
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
                    // outline is a divider colour, not a text colour: it disappears on the panel
                    // surface. Muted-on-surface keeps the row legible while staying secondary.
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (message.stepsExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                message.steps.forEach { step -> ToolStepRow(step, compact) }
            }
        }
    }
}

@Composable
private fun ToolStepRow(step: TurnStep, compact: Boolean) {
    val dot = when {
        step.isError -> MaterialTheme.colorScheme.error
        step.finished -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(horizontal = 7.dp, vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 4.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(dot),
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = step.label,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (step.detail.isNotBlank()) {
                Text(
                    text = step.detail.take(if (compact) 80 else 220),
                    style = MaterialTheme.typography.labelSmall,
                    // Same reason as the collapsed label: outline has almost no contrast against the
                    // panel surface, so detail lines were being drawn but not read.
                    color = if (step.isError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
                    },
                    fontFamily = FontFamily.Monospace,
                    maxLines = if (compact) 2 else 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (step.finished && !step.isError) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                modifier = Modifier.size(13.dp),
            )
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
                TextButton(
                    onClick = { onConfirm(false) },
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) {
                    Text("Deny", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(
                    onClick = { onConfirm(true) },
                    contentPadding = PaddingValues(horizontal = 10.dp),
                ) {
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
    val density = LocalDensity.current.density
    // Measured rather than assumed: the field grows with the text, and the send button has to follow it.
    var fieldHeight by remember { mutableIntStateOf(0) }
    val centered = ComposerLayout.sendButtonCentered(fieldHeight, density)

    Row(
        modifier = Modifier.fillMaxWidth().padding(6.dp),
        // Centred against a single line, bottom-aligned once the field wraps: a centred button next to a
        // four-line field ends up floating halfway up the text, which is harder to hit than it looks.
        verticalAlignment = if (centered) Alignment.CenterVertically else Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .heightIn(max = 96.dp)
                .onSizeChanged { fieldHeight = it.height },
            enabled = enabled,
            placeholder = { Text("Ask about the terminal…", style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
        )
        Spacer(Modifier.width(4.dp))
        FilledIconButton(
            onClick = onSend,
            enabled = enabled && value.isNotBlank(),
            modifier = Modifier
                .size(40.dp)
                .semantics { contentDescription = "Send" },
        ) {
            Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
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
