package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.plugin.PluginCatalogHeader
import com.helix.app.plugin.PluginService
import com.helix.extensions.skills.SkillListItem
import com.helix.extensions.skills.SkillSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Package-owned skills stay with their package; independent content uses the same card hierarchy. */
@Composable
@Suppress("FunctionName", "TooGenericExceptionCaught")
internal fun StandaloneSkillsSection(
    service: PluginService,
    onUse: (com.helix.extensions.skills.SkillKey) -> Unit,
    query: String = "",
    onSearchResult: ((Boolean?) -> Unit)? = null,
) {
    var rows by remember { mutableStateOf<List<SkillListItem>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(service) {
        try {
            rows = withContext(Dispatchers.IO) { service.standaloneSkills() }
            loaded = true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            failed = true
        }
    }
    if (failed) Text(stringResource(R.string.connector_failed))
    val matches = matchingStandaloneSkills(rows, query)
    LaunchedEffect(query, matches, loaded, failed) {
        onSearchResult?.invoke(matches.isNotEmpty().takeIf { loaded && !failed })
    }
    StandaloneSkillCatalog(rows, onUse, query, service)
}

@Composable
@Suppress("FunctionName")
internal fun StandaloneSkillCatalog(
    rows: List<SkillListItem>,
    onUse: (com.helix.extensions.skills.SkillKey) -> Unit,
    query: String = "",
    service: PluginService? = null,
) {
    val matches = matchingStandaloneSkills(rows, query)
    val builtIns = matches.filter { it.key.source == SkillSource.BUILT_IN }
    matches.filter { it.key.source != SkillSource.BUILT_IN }.forEach { StandaloneSkillCard(it, onUse, service) }
    if (builtIns.isNotEmpty()) {
        var expanded by rememberSaveable(query) { mutableStateOf(query.isNotBlank()) }
        val open = expanded
        PluginCatalogHeader(
            stringResource(R.string.standalone_skills_builtin),
            open,
            Modifier.testTag("builtin-skills-group"),
        ) { expanded = !expanded }
        if (open) builtIns.forEach { StandaloneSkillCard(it, onUse, service) }
    }
}

@Composable
@Suppress("FunctionName")
private fun StandaloneSkillCard(
    row: SkillListItem,
    onUse: (com.helix.extensions.skills.SkillKey) -> Unit,
    service: PluginService?,
) {
    var expanded by rememberSaveable(row.key) { mutableStateOf(false) }
    val (title, summary) = skillCatalogPresentation(row)
    val id = "${row.key.source}-${row.key.name}"
    HelixCard(Modifier.fillMaxWidth().testTag("skill-card-$id")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PluginCatalogHeader(title, expanded, Modifier.testTag("skill-header-$id")) { expanded = !expanded }
            Text(
                stringResource(
                    when (row.key.source) {
                        SkillSource.BUILT_IN -> R.string.standalone_skill_builtin
                        SkillSource.USER_IMPORTED -> R.string.standalone_skills_installed
                        SkillSource.PROJECT -> R.string.standalone_skill_project
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(summary, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    { onUse(row.key) },
                    Modifier.testTag("skill-use-$id"),
                ) { Text(stringResource(R.string.extensions_use)) }
            }
            if (expanded) {
                Text(stringResource(R.string.plugin_details), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.standalone_skill_identifier, row.key.name))
                service?.let { SkillSupportDetails(it.skillSupport, row.key) }
            }
        }
    }
}

@Composable
private fun matchingStandaloneSkills(
    rows: List<SkillListItem>,
    query: String,
): List<SkillListItem> =
    rows.filter { row ->
        val (title, summary) = skillCatalogPresentation(row)
        listOf(title, summary, row.key.name, row.description).any { it.contains(query.trim(), true) }
    }
