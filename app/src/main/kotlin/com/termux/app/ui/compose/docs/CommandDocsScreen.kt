package com.termux.app.ui.compose.docs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

    val visible = remember(query, allCommands) {
        if (query.isBlank()) {
            allCommands
        } else {
            val lower = query.trim().lowercase()
            val result = ArrayList<String>()
            for (command in allCommands) {
                if (command.lowercase().contains(lower)) result.add(command)
            }
            if (result.isEmpty()) {
                for (command in allCommands) {
                    val summary = summaries[command]
                    if (summary != null && summary.lowercase().contains(lower)) {
                        result.add(command)
                    }
                }
            }
            result
        }
    }

    val current = selected
    if (current == null) {
        CommandListContent(
            commands = visible,
            summaries = summaries,
            query = query,
            loading = loading,
            onQueryChange = { query = it },
            onSelect = { selected = it },
            onOpenWiki = onOpenWiki,
            onBack = onBack
        )
    } else {
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
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
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
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(commands) { command ->
                        CommandRow(
                            command = command,
                            summary = summaries[command] ?: "",
                            onClick = { onSelect(command) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = command,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
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
                title = { Text(command, fontWeight = FontWeight.SemiBold) },
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
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(blocks) { block ->
                        MarkdownBlock(block)
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownBlock(block: MdBlock) {
    when (block) {
        is MdBlock.Heading -> {
            val style: TextStyle = when (block.level) {
                1 -> MaterialTheme.typography.headlineSmall
                2 -> MaterialTheme.typography.titleLarge
                3 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }
            Column {
                if (block.level <= 2) Spacer(modifier = Modifier.height(8.dp))
                MarkdownInline(block.text, style, MaterialTheme.colorScheme.onSurface)
            }
        }

        is MdBlock.Paragraph -> {
            MarkdownInline(
                block.text,
                MaterialTheme.typography.bodyMedium,
                MaterialTheme.colorScheme.onSurface
            )
        }

        is MdBlock.CodeBlock -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                    .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
            ) {
                Text(
                    text = block.code,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 18.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        is MdBlock.BulletItem -> {
            Row(modifier = Modifier.padding(start = (block.indent * 12).dp)) {
                Text("•", color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                MarkdownInline(
                    block.text,
                    MaterialTheme.typography.bodyMedium,
                    MaterialTheme.colorScheme.onSurface
                )
            }
        }

        is MdBlock.NumberedItem -> {
            Row(modifier = Modifier.padding(start = (block.indent * 12).dp)) {
                Text(
                    text = "${block.number}.",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.width(8.dp))
                MarkdownInline(
                    block.text,
                    MaterialTheme.typography.bodyMedium,
                    MaterialTheme.colorScheme.onSurface
                )
            }
        }

        is MdBlock.Quote -> {
            Row(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(20.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                )
                Spacer(modifier = Modifier.width(10.dp))
                MarkdownInline(
                    block.text,
                    MaterialTheme.typography.bodyMedium,
                    MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        else -> {
            HorizontalDivider(
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

@Composable
private fun MarkdownInline(
    text: String,
    style: TextStyle,
    color: Color
) {
    val spans = remember(text) { MarkdownParser.parseInline(text) }
    val annotated = buildAnnotatedString {
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
                        background = Color(0x1A888888)
                    )
                ) {
                    append(span.text)
                }
                is MdSpan.Link -> withStyle(SpanStyle(color = Color(0xFF2DD4BF))) {
                    append(span.text)
                }
            }
        }
    }
    Text(text = annotated, style = style, color = color)
}
