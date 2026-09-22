@file:Suppress("FunctionName", "ktlint:standard:function-naming", "LongMethod")

package com.helix.feature.browser.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.helix.feature.browser.R
import com.helix.feature.browser.storage.UserScript
import java.util.UUID

/**
 * Dialog for managing Tampermonkey-style user scripts.
 */
@Composable
fun BrowserUserScriptsDialog(
    scripts: List<UserScript>,
    onToggleScript: (String) -> Unit,
    onSaveScript: (UserScript) -> Unit,
    onDeleteScript: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editingScript by remember { mutableStateOf<UserScript?>(null) }
    var isAddingNew by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "📜 " + stringResource(R.string.browser_scripts_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { isAddingNew = true }) {
                        Text(
                            text = "＋ " + stringResource(R.string.browser_add_script),
                            fontSize = 13.sp,
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.browser_dismiss))
                    }
                }
            }

            if (scripts.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "暂无用户脚本，点击右上角添加",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(scripts, key = { it.id }) { script ->
                        UserScriptRow(
                            script = script,
                            onToggle = { onToggleScript(script.id) },
                            onEdit = { editingScript = script },
                            onDelete = { onDeleteScript(script.id) },
                        )
                    }
                }
            }
        }
    }

    if (isAddingNew) {
        ScriptEditorDialog(
            script =
                UserScript(
                    id = UUID.randomUUID().toString(),
                    name = "",
                    matchPattern = "*",
                    code =
                        "// ==UserScript==\n" +
                            "// @name New Script\n" +
                            "// @match *\n" +
                            "// ==/UserScript==\n\n" +
                            "console.log('Hello Helix!');",
                ),
            isNew = true,
            onDismiss = { isAddingNew = false },
            onSave = {
                onSaveScript(it)
                isAddingNew = false
            },
        )
    }

    editingScript?.let { script ->
        ScriptEditorDialog(
            script = script,
            isNew = false,
            onDismiss = { editingScript = null },
            onSave = {
                onSaveScript(it)
                editingScript = null
            },
        )
    }
}

@Composable
private fun UserScriptRow(
    script: UserScript,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onEdit)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = script.name,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Match: ${script.matchPattern}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Switch(
            checked = script.enabled,
            onCheckedChange = { onToggle() },
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        Box(
            modifier =
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "🗑️", fontSize = 14.sp)
        }
    }
}

@Composable
private fun ScriptEditorDialog(
    script: UserScript,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (UserScript) -> Unit,
) {
    var name by remember { mutableStateOf(script.name) }
    var pattern by remember { mutableStateOf(script.matchPattern) }
    var code by remember { mutableStateOf(script.code) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isNew) {
                    stringResource(
                        R.string.browser_add_script,
                    )
                } else {
                    stringResource(R.string.browser_edit_script)
                },
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.browser_script_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text(stringResource(R.string.browser_script_match)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text(stringResource(R.string.browser_script_code)) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                    textStyle =
                        androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        ),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank() && code.isNotBlank()) {
                        onSave(script.copy(name = name, matchPattern = pattern, code = code))
                    }
                },
            ) {
                Text(stringResource(R.string.browser_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.browser_cancel))
            }
        },
    )
}
