package com.helix.app.plugin

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.extensions.plugin.PluginBundledSkill
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only package contents, including while disabled; inspecting never selects a plugin. */
@Composable
@Suppress("FunctionName")
internal fun PluginBundledContents(
    service: PluginService,
    pluginId: String,
) {
    var tools by remember(pluginId) { mutableStateOf(emptyList<Pair<String, String>>()) }
    var skills by remember(pluginId) { mutableStateOf(emptyList<PluginBundledSkill>()) }
    var loaded by remember(pluginId) { mutableStateOf(false) }
    var failed by remember(pluginId) { mutableStateOf(false) }
    LaunchedEffect(service, pluginId) {
        try {
            tools = withContext(Dispatchers.IO) { service.bundledToolDescriptions(pluginId) }
            skills = withContext(Dispatchers.IO) { service.bundledSkillContents(pluginId) }
            loaded = true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: RuntimeException) {
            failed = true
        }
    }
    Column(Modifier.testTag("plugin-contents-$pluginId")) {
        if (failed) {
            Text(stringResource(R.string.connector_failed))
            return@Column
        }
        if (!loaded) {
            androidx.compose.material3.CircularProgressIndicator()
            return@Column
        }
        Text(stringResource(R.string.plugin_contents_tools, tools.size))
        tools.forEach { (name, description) ->
            var open by remember(name) { mutableStateOf(false) }
            TextButton({ open = !open }) { Text(name) }
            if (open) Text(description, Modifier.testTag("plugin-tool-description-$name"))
        }
        Text(stringResource(R.string.plugin_contents_skills, skills.size))
        Text(stringResource(R.string.plugin_contents_skill_hint))
        skills.forEach { skill ->
            var open by remember(skill.name) { mutableStateOf(false) }
            TextButton({ open = !open }) { Text(skill.name) }
            Text(skill.description)
            if (open) Text(skill.content, Modifier.testTag("plugin-skill-content-${skill.name}"))
        }
    }
}
