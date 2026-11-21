package com.termux.app.ui.compose.docs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Command documentation browser.
 *
 * Shows the bundled command list with search, and renders the selected
 * command's documentation with native Compose components.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandDocsScreen(
    repository: CommandDocRepository,
    onOpenWiki: () -> Unit,
    onBack: () -> Unit
) {
    var allCommands by remember { mutableStateOf<List<String>>(emptyList()) }
    var summaries by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            val commands = repository.getCommands()
            val map = HashMap<String, String>()
            for (command in commands) {
                map[command] = repository.loadSummary(command)
            }
            Pair(commands, map)
        }
        allCommands = loaded.first
        summaries = loaded.second
        loading = false
    }

    val visible = remember(query, allCommands, summaries) {
        if (query.isBlank()) {
            allCommands
        } else {
            val lower = query.trim().lowercase()
            val byName = ArrayList<String>()
            val bySummary = ArrayList<String>()
            for (command in allCommands) {
                if (command.lowercase().contains(lower)) {
                    byName.add(command)
                } else {
                    val summary = summaries[command]
                    if (summary != null && summary.lowercase().contains(lower)) {
                        bySummary.add(command)
                    }
                }
            }
            byName.addAll(bySummary)
            byName
        }
    }

    val current = selected
    if (current == null) {
        // Back from the list exits the screen entirely.
        BackHandler { onBack() }
        CommandListContent(
            commands = visible,
            summaries = summaries,
            query = query,
            loading = loading,
            listState = listState,
            onQueryChange = { query = it },
            onSelect = {
                selected = it
            },
            onOpenWiki = onOpenWiki,
            onBack = onBack
        )
    } else {
        // Back from a detail returns to the list instead of leaving the screen.
        BackHandler { selected = null }
        CommandDetailContent(
            command = current,
            repository = repository,
            onBack = { selected = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommandListContent(
    commands: List<String>,
    summaries: Map<String, String>,
    query: String,
    loading: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onQueryChange: (String) -> Unit,
    onSelect: (String) -> Unit,
    onOpenWiki: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Command docs", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = onOpenWiki) { Text("Wiki") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                placeholder = { Text("Search commands") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { })
            )

            if (!loading && commands.isNotEmpty()) {
                Text(
                    text = "${commands.size} commands",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, bottom = 6.dp)
                )
            }

            if (loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (commands.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No command documentation found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(commands) { command ->
                        CommandRow(
                            command = command,
                            summary = summaries[command] ?: "",
                            onClick = { onSelect(command) }
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            thickness = 0.5.dp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommandRow(
    command: String,
    summary: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 20.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(36.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(10.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = command.take(2),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = command,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace
            )
            if (summary.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommandDetailContent(
    command: String,
    repository: CommandDocRepository,
    onBack: () -> Unit
) {
    var blocks by remember { mutableStateOf<List<MdBlock>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(command) {
        val parsed = withContext(Dispatchers.IO) {
            val markdown = repository.loadMarkdown(command)
            if (markdown == null) ArrayList<MdBlock>() else MarkdownParser.parse(markdown)
        }
        blocks = parsed
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = command,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (blocks.isEmpty()) {
                Text(
                    "Documentation not available",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // SelectionContainer makes the whole document selectable so the
                // text can be copied with a long press.
                SelectionContainer {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp
                        )
                    ) {
                        items(blocks) { block ->
                            MarkdownBlock(block)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownBlock(block: MdBlock) {
    when (block) {
        is MdBlock.Heading -> HeadingBlock(block)
        is MdBlock.Paragraph -> Text(
            text = inlineText(block.text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 22.sp
        )

        is MdBlock.CodeBlock -> CodeBlockView(block.code)
        is MdBlock.BulletItem -> ListItemView(block.indent, "\u2022", block.text)
        is MdBlock.NumberedItem -> ListItemView(block.indent, "${block.number}.", block.text)
        is MdBlock.Quote -> QuoteView(block.text)
        else -> HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
    }
}

@Composable
private fun HeadingBlock(block: MdBlock.Heading) {
    val style: TextStyle = when (block.level) {
        1 -> MaterialTheme.typography.headlineMedium
        2 -> MaterialTheme.typography.titleLarge
        3 -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.titleSmall
    }
    Column(modifier = Modifier.padding(top = if (block.level <= 2) 20.dp else 14.dp, bottom = 6.dp)) {
        Text(
            text = inlineText(block.text),
            style = style,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )
        if (block.level == 1) {
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .width(44.dp)
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
            )
        }
    }
}

@Composable
private fun CodeBlockView(code: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    // Reset the "copied" hint after a moment.
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(12.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (copied) "Copied" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(code))
                    copied = true
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy code",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Text(
            text = code,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                lineHeight = 19.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 12.dp)
        )
    }
}

@Composable
private fun ListItemView(indent: Int, marker: String, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (indent * 14).dp, top = 3.dp, bottom = 3.dp)
    ) {
        Text(
            text = marker,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(24.dp)
        )
        Text(
            text = inlineText(text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 22.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun QuoteView(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(20.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = inlineText(text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontStyle = FontStyle.Italic
        )
    }
}

@Composable
private fun inlineText(text: String): androidx.compose.ui.text.AnnotatedString {
    val spans = remember(text) { MarkdownParser.parseInline(text) }
    return buildAnnotatedString {
        for (span in spans) {
            when (span) {
                is MdSpan.Normal -> append(span.text)
                is MdSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(span.text)
                }
                is MdSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(span.text)
                }
                is MdSpan.Code -> withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = Color(0x22888888)
                    )
                ) {
                    append(span.text)
                }
                is MdSpan.Link -> withStyle(
                    SpanStyle(color = Color(0xFF2DD4BF))
                ) {
                    append(span.text)
                }
            }
        }
    }
}
