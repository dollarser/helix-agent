package com.helix.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.skills.SkillSupportService
import com.helix.app.skills.SkillSupportSnapshot
import com.helix.extensions.skills.SkillFileKind
import com.helix.extensions.skills.SkillKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
@Suppress("FunctionName", "TooGenericExceptionCaught")
internal fun SkillSupportDetails(
    service: SkillSupportService,
    key: SkillKey,
) {
    var snapshot by remember(key) { mutableStateOf<SkillSupportSnapshot?>(null) }
    var failed by remember(key) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(service, key, retry) {
        try {
            snapshot = withContext(Dispatchers.IO) { service.inspect(key) }
            failed = false
        } catch (
            cancel: CancellationException,
        ) {
            throw cancel
        } catch (_: Exception) {
            snapshot = null
            failed = true
        }
    }
    snapshot?.let { SkillSupportFacts(it) }
    if (failed) {
        Text(stringResource(R.string.skill_support_failed), color = MaterialTheme.colorScheme.error)
        TextButton({ retry++ }) { Text(stringResource(R.string.skill_support_retry)) }
    }
}

@Composable
@Suppress("FunctionName")
internal fun SkillSupportFacts(snapshot: SkillSupportSnapshot) {
    val document = snapshot.document
    if (snapshot.unsupported.isNotEmpty()) {
        Text(
            stringResource(R.string.skill_support_unsupported, snapshot.unsupported.sorted().joinToString()),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("skill-unsupported"),
        )
    }
    if (snapshot.unapplied.isNotEmpty()) {
        Text(
            stringResource(R.string.skill_support_unapplied, snapshot.unapplied.sorted().joinToString()),
            modifier = Modifier.testTag("skill-unapplied"),
        )
    }
    document.compatibility?.let { Text(stringResource(R.string.skill_support_compatibility, it)) }
    document.allowedTools?.takeIf { it.isNotBlank() }?.let { Text(stringResource(R.string.skill_support_tools, it)) }
    if (document.body.contains("$" + "ARGUMENTS") || document.body.contains("$" + "{CLAUDE_") ||
        document.body.contains("!`")
    ) {
        Text(stringResource(R.string.skill_support_parameters_literal))
    }
    val scripts =
        snapshot.files.filter {
            it.kind == SkillFileKind.SCRIPT && it.relativePath != "scripts/manifest.json"
        }
    if (scripts.isNotEmpty()) {
        Text(stringResource(R.string.skill_support_scripts, scripts.joinToString { it.relativePath }))
        Text(
            stringResource(
                if (snapshot.scriptRunnerProvided) {
                    R.string.skill_support_script_runner
                } else {
                    R.string.skill_support_script_no_runner
                },
            ),
        )
        Text(stringResource(R.string.skill_support_script_environment))
    }
    snapshot.web?.let { web ->
        Text(
            stringResource(
                if (web.provided) {
                    R.string.skill_support_web_provided
                } else {
                    R.string.skill_support_web_missing
                },
            ),
            Modifier.testTag("skill-web-dependency"),
        )
    }
}
