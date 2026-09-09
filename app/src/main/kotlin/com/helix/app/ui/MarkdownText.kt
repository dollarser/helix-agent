package com.helix.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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

@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod")
internal fun MarkdownText(
    source: String,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(source) { markdownBlocks(source) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block.kind) {
                MarkdownBlock.Kind.CODE -> {
                    Text(
                        block.text,
                        fontFamily = FontFamily.Monospace,
                        modifier =
                            Modifier
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(8.dp)
                                .horizontalScroll(rememberScrollState()),
                    )
                }

                MarkdownBlock.Kind.TABLE -> {
                    MarkdownTable(block.text)
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
                    Text(
                        markdownInline(block.text),
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
private fun MarkdownTable(text: String) {
    Column(Modifier.horizontalScroll(rememberScrollState())) {
        text.lines().forEachIndexed { index, line ->
            Row {
                line.trim().trim('|').split('|').forEach { cell ->
                    Text(
                        markdownInline(cell.trim()),
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
