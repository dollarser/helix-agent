package com.helix.app.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.helix.app.R

/** Keep long task identifiers readable without permanently displacing task controls. */
@Composable
@Suppress("FunctionName")
internal fun ExpandableSummary(
    text: String,
    style: TextStyle,
    tag: String,
    modifier: Modifier = Modifier,
    collapsedLines: Int = 2,
) {
    var expanded by remember(text) { mutableStateOf(false) }
    var overflow by remember(text) { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.Top) {
        Text(
            text,
            style = style,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflow = it.hasVisualOverflow },
            modifier = Modifier.weight(1f).testTag(tag),
        )
        if (overflow || expanded) {
            IconButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("$tag-toggle")) {
                Icon(
                    painterResource(R.drawable.ic_expand_summary),
                    modifier = Modifier.rotate(if (expanded) 180f else 0f),
                    contentDescription =
                        stringResource(if (expanded) R.string.summary_collapse else R.string.summary_expand),
                )
            }
        }
    }
}
