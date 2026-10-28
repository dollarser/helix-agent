package com.helix.app.knowledge

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.helix.app.R

/** One scrolling document, rather than a form containing a second scrolling text box. */
@Composable
@Suppress("FunctionName", "LongMethod", "LongParameterList")
internal fun KnowledgeWritingCanvas(
    title: TextFieldValue,
    onTitle: (TextFieldValue) -> Unit,
    body: TextFieldValue,
    onBody: (TextFieldValue) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val bodyFocus = remember { FocusRequester() }
    val titleLabel = stringResource(R.string.knowledge_editor_title_hint)
    val bodyLabel = stringResource(R.string.knowledge_markdown_body)
    BoxWithConstraints(modifier.fillMaxWidth().testTag("knowledge-note-editor")) {
        val pageHeight = maxHeight
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 48.dp)
                .testTag("knowledge-writing-scroll"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicTextField(
                title,
                onTitle,
                enabled = enabled,
                textStyle = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { bodyFocus.requestFocus() }),
                modifier =
                    Modifier
                        .widthIn(max = 760.dp)
                        .fillMaxWidth()
                        .padding(top = 24.dp, bottom = 20.dp)
                        .semantics { contentDescription = titleLabel }
                        .testTag("knowledge-note-title"),
                decorationBox = { field ->
                    Box {
                        if (title.text.isEmpty()) {
                            Text(
                                titleLabel,
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f),
                            )
                        }
                        field()
                    }
                },
            )
            BasicTextField(
                body,
                onBody,
                enabled = enabled,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier =
                    Modifier
                        .widthIn(max = 760.dp)
                        .fillMaxWidth()
                        .heightIn(min = (pageHeight - 120.dp).coerceAtLeast(180.dp))
                        .focusRequester(bodyFocus)
                        .semantics { contentDescription = bodyLabel }
                        .testTag("knowledge-note-body"),
                decorationBox = { field ->
                    Box {
                        if (body.text.isEmpty()) {
                            Text(
                                stringResource(R.string.knowledge_editor_body_hint),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f),
                            )
                        }
                        field()
                    }
                },
            )
        }
    }
}
