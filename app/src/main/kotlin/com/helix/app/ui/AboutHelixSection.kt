package com.helix.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
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
    val context = LocalContext.current
    val version =
        remember(context) {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName.orEmpty()} (${info.longVersionCode})"
        }
    AboutHelixContent(
        version,
        onOpen = { openProjectLink(context, it.url) },
    )
}

@Composable
@Suppress("FunctionName")
internal fun AboutHelixContent(
    version: String,
    onOpen: (AboutHelixLink) -> Boolean,
) {
    var failed by remember { mutableStateOf(false) }
    SettingsGroup {
        Column(Modifier.fillMaxWidth().testTag("settings-about"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.about_helix_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.about_helix_summary), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.about_helix_version, version), style = MaterialTheme.typography.bodySmall)
            AboutHelixLink.entries.forEach { link ->
                TextButton({ failed = !onOpen(link) }, Modifier.testTag(link.tag)) {
                    Text(stringResource(link.label))
                }
            }
            if (failed) {
                Text(
                    stringResource(R.string.about_link_failed),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("about-link-error"),
                )
            }
        }
    }
}

internal enum class AboutHelixLink(
    val label: Int,
    val url: String,
    val tag: String,
) {
    AUTHOR(R.string.about_helix_author, "https://github.com/dollarser", "about-author"),
    PROJECT(R.string.about_helix_project, "https://github.com/dollarser/helix-agent", "about-project"),
    UPDATES(R.string.about_helix_updates, "https://github.com/dollarser/helix-agent/releases", "about-updates"),
    FEEDBACK(R.string.about_helix_feedback, "https://github.com/dollarser/helix-agent/issues", "about-feedback"),
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
