package com.helix.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.helix.app.R

internal fun highlightAnnotatedString(
    source: AnnotatedString,
    query: String,
    highlightStyle: SpanStyle,
): AnnotatedString {
    if (query.isBlank() || source.text.isEmpty()) return source
    return buildAnnotatedString {
        append(source)
        var startIndex = 0
        while (startIndex < source.text.length) {
            val index = source.text.indexOf(query, startIndex, ignoreCase = true)
            if (index == -1) break
            addStyle(highlightStyle, index, index + query.length)
            startIndex = index + query.length
        }
    }
}

@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod")
internal fun MarkdownText(
    source: String,
    modifier: Modifier = Modifier,
    searchQuery: String? = null,
) {
    val blocks = remember(source) { markdownBlocks(source) }
    val highlightStyle =
        SpanStyle(
            background = MaterialTheme.colorScheme.primaryContainer,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Bold,
        )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block.kind) {
                MarkdownBlock.Kind.CODE -> {
                    MarkdownCodeBlock(block.text, block.language, searchQuery)
                }

                MarkdownBlock.Kind.TABLE -> {
                    MarkdownTable(block.text, searchQuery)
                }

                MarkdownBlock.Kind.RULE -> {
                    HorizontalDivider()
                }

                else -> {
                    val style =
                        when (block.kind) {
                            MarkdownBlock.Kind.HEADING -> {
                                when (block.level) {
                                    1 -> MaterialTheme.typography.headlineSmall
                                    2 -> MaterialTheme.typography.titleLarge
                                    else -> MaterialTheme.typography.titleMedium
                                }
                            }

                            else -> {
                                MaterialTheme.typography.bodyLarge
                            }
                        }
                    val inline = markdownInline(block.text)
                    val annotated =
                        if (!searchQuery.isNullOrBlank()) {
                            highlightAnnotatedString(inline, searchQuery, highlightStyle)
                        } else {
                            inline
                        }
                    Text(
                        annotated,
                        style = style,
                        fontStyle = if (block.kind == MarkdownBlock.Kind.QUOTE) FontStyle.Italic else null,
                    )
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun MarkdownTable(
    text: String,
    searchQuery: String? = null,
) {
    val highlightStyle =
        SpanStyle(
            background = MaterialTheme.colorScheme.primaryContainer,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Bold,
        )
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        text.lines().forEachIndexed { index, line ->
            Row {
                line.trim().trim('|').split('|').forEach { cell ->
                    val inline = markdownInline(cell.trim())
                    val annotated =
                        if (!searchQuery.isNullOrBlank()) {
                            highlightAnnotatedString(inline, searchQuery, highlightStyle)
                        } else {
                            inline
                        }
                    Text(
                        annotated,
                        fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.width(160.dp).padding(8.dp),
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

@Suppress("CyclomaticComplexMethod") // explicit branches for presentation-only Markdown tokens
internal fun markdownInline(text: String): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val marker =
                when {
                    text.startsWith("**", i) -> "**"
                    text.startsWith("__", i) -> "__"
                    text[i] == '`' -> "`"
                    text[i] == '*' -> "*"
                    else -> null
                }
            val end = marker?.let { text.indexOf(it, i + it.length) } ?: -1
            when {
                text[i] == '\\' && i + 1 < text.length -> {
                    append(text[i + 1])
                    i += 2
                }

                marker != null && end > i + marker.length -> {
                    val style =
                        when (marker) {
                            "`" -> SpanStyle(fontFamily = FontFamily.Monospace)
                            "*" -> SpanStyle(fontStyle = FontStyle.Italic)
                            else -> SpanStyle(fontWeight = FontWeight.Bold)
                        }
                    withStyle(style) { append(text.substring(i + marker.length, end)) }
                    i = end + marker.length
                }

                text[i] == '[' && text.indexOf("](", i) > i -> {
                    val labelEnd = text.indexOf("](", i)
                    val urlEnd = text.indexOf(')', labelEnd + 2)
                    if (urlEnd < 0) {
                        append(text[i++])
                        continue
                    }
                    val url = text.substring(labelEnd + 2, urlEnd)
                    if (url.startsWith("https://") || url.startsWith("http://")) {
                        withLink(LinkAnnotation.Url(url)) { append(text.substring(i + 1, labelEnd)) }
                    } else {
                        append(text.substring(i, urlEnd + 1))
                    }
                    i = urlEnd + 1
                }

                else -> {
                    append(text[i++])
                }
            }
        }
    }

private const val COPY_FEEDBACK_DURATION_MS = 1500L

@Composable
@Suppress("FunctionName")
private fun MarkdownCodeHeader(
    language: String?,
    copied: Boolean,
    onCopy: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = language?.uppercase() ?: stringResource(R.string.code_block_default_lang),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier =
                Modifier
                    .clickable(onClick = onCopy)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .testTag("code-block-copy"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            val iconRes = if (copied) R.drawable.ic_check else R.drawable.ic_chat_copy
            val labelRes = if (copied) R.string.code_copied else R.string.code_copy
            Icon(
                painter = painterResource(iconRes),
                contentDescription = stringResource(labelRes),
                modifier = Modifier.size(14.dp),
                tint = tint,
            )
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelSmall,
                color = tint,
            )
        }
    }
}

private const val CODE_BLOCK_COLLAPSE_THRESHOLD = 18

@Composable
@Suppress("FunctionName")
private fun MarkdownCodeBlock(
    code: String,
    language: String?,
    searchQuery: String? = null,
) {
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val lines = remember(code) { code.lines() }
    val canCollapse = lines.size > CODE_BLOCK_COLLAPSE_THRESHOLD
    var expanded by remember(code) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(COPY_FEEDBACK_DURATION_MS)
            copied = false
        }
    }

    val displayLines =
        if (!canCollapse || expanded) {
            lines
        } else {
            lines.take(CODE_BLOCK_COLLAPSE_THRESHOLD)
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(8.dp),
                ).padding(vertical = 4.dp),
    ) {
        MarkdownCodeHeader(
            language = language,
            copied = copied,
            onCopy = {
                clipboardManager.setText(AnnotatedString(code))
                copied = true
            },
        )
        HorizontalDivider(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        MarkdownCodeContent(
            displayLines = displayLines,
            searchQuery = searchQuery,
        )
        if (canCollapse) {
            MarkdownCodeExpandToggle(
                lineCount = lines.size,
                expanded = expanded,
                onToggle = { expanded = !expanded },
            )
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun MarkdownCodeContent(
    displayLines: List<String>,
    searchQuery: String?,
) {
    val highlightStyle =
        SpanStyle(
            background = MaterialTheme.colorScheme.primaryContainer,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Bold,
        )
    val lineNumberText =
        remember(displayLines.size) {
            (1..displayLines.size).joinToString("\n")
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .horizontalScroll(rememberScrollState()),
    ) {
        Text(
            text = lineNumberText,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.padding(end = 12.dp),
        )
        val codeText = displayLines.joinToString("\n")
        val annotated =
            if (!searchQuery.isNullOrBlank()) {
                highlightAnnotatedString(AnnotatedString(codeText), searchQuery, highlightStyle)
            } else {
                AnnotatedString(codeText)
            }
        Text(
            text = annotated,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
@Suppress("FunctionName")
private fun MarkdownCodeExpandToggle(
    lineCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("code-block-expand-toggle"),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val label =
            if (expanded) {
                stringResource(R.string.code_block_collapse)
            } else {
                stringResource(R.string.code_block_expand, lineCount)
            }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Icon(
            painter = painterResource(R.drawable.ic_expand_summary),
            contentDescription = label,
            modifier =
                Modifier
                    .size(16.dp)
                    .rotate(if (expanded) 180f else 0f),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}
