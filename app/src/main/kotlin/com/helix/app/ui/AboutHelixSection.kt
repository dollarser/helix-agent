package com.helix.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.helix.app.R

@Composable
@Suppress("FunctionName")
internal fun AboutHelixSection() {
    var open by remember { mutableStateOf(false) }
    val context = LocalContext.current
    HorizontalDivider()
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().testTag("settings-about")) {
        Text(stringResource(R.string.about_helix_title))
    }
    if (open) {
        val version =
            remember(context) {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                "${info.versionName.orEmpty()} (${info.longVersionCode})"
            }
        AboutHelixDialog(
            version = version,
            onDismiss = { open = false },
            onProject = { openProjectLink(context, "https://github.com/dollarser/helix-agent") },
            onDeveloper = { openProjectLink(context, "https://github.com/dollarser") },
        )
    }
}

@Composable
@Suppress("FunctionName")
internal fun AboutHelixDialog(
    version: String,
    onDismiss: () -> Unit,
    onProject: () -> Boolean,
    onDeveloper: () -> Boolean,
) {
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.about_helix_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.about_helix_version, version))
                Text("github.com/dollarser/helix-agent", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { failed = !onProject() }, modifier = Modifier.testTag("about-project")) {
                    Text(stringResource(R.string.about_helix_project))
                }
                TextButton(onClick = { failed = !onDeveloper() }, modifier = Modifier.testTag("about-developer")) {
                    Text(stringResource(R.string.about_helix_developer))
                }
                if (failed) {
                    Text(
                        stringResource(R.string.about_link_failed),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("about-link-error"),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_details_close)) } },
    )
}

private fun openProjectLink(
    context: Context,
    url: String,
): Boolean =
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
