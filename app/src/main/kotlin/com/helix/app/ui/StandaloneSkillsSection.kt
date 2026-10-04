package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.plugin.PluginService
import com.helix.extensions.skills.SkillListItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Package-owned skills are managed with their package; independent ownership remains visible. */
@Composable
@Suppress("FunctionName", "TooGenericExceptionCaught")
internal fun StandaloneSkillsSection(
    service: PluginService,
    onUse: () -> Unit,
    query: String = "",
) {
    var rows by remember { mutableStateOf<List<SkillListItem>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(service) {
        try {
            rows = withContext(Dispatchers.IO) { service.standaloneSkills() }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            failed = true
        }
    }
    if (failed) Text(stringResource(R.string.connector_failed))
    rows.filter { it.key.name.contains(query, true) || it.description.contains(query, true) }.forEach { row ->
        Column {
            Text(row.key.name)
            Text(row.description)
            OutlinedButton(onClick = onUse) { Text(stringResource(R.string.extensions_use)) }
        }
    }
}
