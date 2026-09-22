@file:Suppress("TooManyFunctions")

package com.helix.app.marketplace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
@Suppress("FunctionName", "LongMethod")
fun MarketplaceSection(
    service: MarketplaceService,
    onConfigureRequested: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var selectedFilter by remember { mutableStateOf<MarketplaceItemType?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var statusMap by remember { mutableStateOf<Map<String, MarketplaceItemStatus>>(emptyMap()) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var isActionError by remember { mutableStateOf(false) }
    var installingId by remember { mutableStateOf<String?>(null) }
    var itemToUninstall by remember { mutableStateOf<MarketplaceItem?>(null) }

    LaunchedEffect(refreshTrigger) {
        withContext(Dispatchers.IO) {
            val items = service.items()
            statusMap = items.associate { it.id to service.status(it) }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().testTag("marketplace-section"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.marketplace_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.marketplace_subtitle),
            style = MaterialTheme.typography.bodyMedium,
        )

        MarketplaceSearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
        )

        MarketplaceFilterRow(
            selectedFilter = selectedFilter,
            onSelectFilter = { selectedFilter = it },
        )

        actionMessage?.let { msg ->
            Text(
                text = msg,
                color = if (isActionError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        val allItems = service.items()
        val itemLabels =
            allItems.associate { item ->
                item.id to
                    (stringResource(item.nameRes).lowercase() + " " + stringResource(item.summaryRes).lowercase())
            }

        val query = searchQuery.trim().lowercase()
        val items =
            allItems.filter { item ->
                val matchesType = selectedFilter == null || item.type == selectedFilter
                val label = itemLabels[item.id].orEmpty()
                val matchesQuery =
                    query.isEmpty() ||
                        label.contains(query) ||
                        item.author.lowercase().contains(query) ||
                        item.tags.any { it.lowercase().contains(query) }
                matchesType && matchesQuery
            }

        val successMsg = stringResource(R.string.marketplace_install_success)
        val failMsg = stringResource(R.string.marketplace_install_failed)

        items.forEach { item ->
            val status = statusMap[item.id] ?: MarketplaceItemStatus.NOT_INSTALLED
            val actions =
                MarketplaceCardActions(
                    onInstall = {
                        if (installingId != null) return@MarketplaceCardActions
                        installingId = item.id
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    service.install(item)
                                }
                                actionMessage = successMsg
                                isActionError = false
                                refreshTrigger++
                            } catch (_: Exception) {
                                actionMessage = failMsg
                                isActionError = true
                            } finally {
                                installingId = null
                            }
                        }
                    },
                    onConfigure = onConfigureRequested,
                    onDisable = {
                        service.disable(item)
                        refreshTrigger++
                    },
                    onEnableSkill = {
                        service.enableSkill(item)
                        refreshTrigger++
                    },
                    onUninstallRequested = { itemToUninstall = item },
                    onTagClick = { tag -> searchQuery = tag },
                )
            MarketplaceItemCard(
                item = item,
                status = status,
                isInstalling = installingId == item.id,
                actions = actions,
            )
        }
    }

    itemToUninstall?.let { item ->
        UninstallConfirmDialog(
            item = item,
            onConfirm = {
                service.uninstall(item)
                refreshTrigger++
                itemToUninstall = null
            },
            onDismiss = { itemToUninstall = null },
        )
    }
}

@Composable
@Suppress("FunctionName")
private fun MarketplaceSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().testTag("marketplace-search-input"),
        placeholder = { Text(stringResource(R.string.marketplace_search_placeholder)) },
        singleLine = true,
        trailingIcon = {
            if (query.isNotEmpty()) {
                TextButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.testTag("marketplace-search-clear"),
                ) {
                    Text(stringResource(R.string.marketplace_search_clear))
                }
            }
        },
    )
}

@Composable
@Suppress("FunctionName")
private fun MarketplaceFilterRow(
    selectedFilter: MarketplaceItemType?,
    onSelectFilter: (MarketplaceItemType?) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        FilterChip(
            selected = selectedFilter == null,
            onClick = { onSelectFilter(null) },
            label = { Text(stringResource(R.string.marketplace_filter_all)) },
            modifier = Modifier.testTag("marketplace-filter-all"),
        )
        FilterChip(
            selected = selectedFilter == MarketplaceItemType.CONNECTOR,
            onClick = { onSelectFilter(MarketplaceItemType.CONNECTOR) },
            label = { Text(stringResource(R.string.marketplace_filter_connector)) },
            modifier = Modifier.testTag("marketplace-filter-connector"),
        )
        FilterChip(
            selected = selectedFilter == MarketplaceItemType.MCP,
            onClick = { onSelectFilter(MarketplaceItemType.MCP) },
            label = { Text(stringResource(R.string.marketplace_filter_mcp)) },
            modifier = Modifier.testTag("marketplace-filter-mcp"),
        )
        FilterChip(
            selected = selectedFilter == MarketplaceItemType.SKILL,
            onClick = { onSelectFilter(MarketplaceItemType.SKILL) },
            label = { Text(stringResource(R.string.marketplace_filter_skill)) },
            modifier = Modifier.testTag("marketplace-filter-skill"),
        )
    }
}

private data class MarketplaceCardActions(
    val onInstall: () -> Unit,
    val onConfigure: (() -> Unit)? = null,
    val onDisable: () -> Unit,
    val onEnableSkill: () -> Unit,
    val onUninstallRequested: () -> Unit,
    val onTagClick: (String) -> Unit,
)

@Composable
@Suppress("FunctionName", "LongMethod")
private fun MarketplaceItemCard(
    item: MarketplaceItem,
    status: MarketplaceItemStatus,
    isInstalling: Boolean = false,
    actions: MarketplaceCardActions,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("marketplace-item-${item.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(item.nameRes),
                    style = MaterialTheme.typography.titleSmall,
                )
                StatusBadge(status)
            }

            Text(
                text = stringResource(item.summaryRes),
                style = MaterialTheme.typography.bodyMedium,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TypeTag(item.type)
                AuthTag(item.authRequirement)
                Text(
                    text = "· ${item.author}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                MarketplaceExpandedDetails(
                    item = item,
                    status = status,
                    isInstalling = isInstalling,
                    onInstall = actions.onInstall,
                    onDisable = actions.onDisable,
                    onEnableSkill = actions.onEnableSkill,
                    onUninstall = actions.onUninstallRequested,
                    onTagClick = actions.onTagClick,
                )
            }

            MarketplaceItemActionsRow(
                item = item,
                status = status,
                isInstalling = isInstalling,
                expanded = expanded,
                onToggleExpand = { expanded = !expanded },
                actions = actions,
            )
        }
    }
}

@Composable
@Suppress("FunctionName", "LongMethod")
private fun MarketplaceItemActionsRow(
    item: MarketplaceItem,
    status: MarketplaceItemStatus,
    isInstalling: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    actions: MarketplaceCardActions,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onToggleExpand,
            modifier = Modifier.testTag("marketplace-expand-${item.id}"),
        ) {
            Text(
                if (expanded) {
                    stringResource(R.string.session_export_close)
                } else {
                    stringResource(R.string.command_detail_title)
                },
            )
        }

        when (status) {
            MarketplaceItemStatus.NOT_INSTALLED -> {
                Button(
                    onClick = actions.onInstall,
                    enabled = !isInstalling,
                    modifier = Modifier.testTag("marketplace-install-${item.id}"),
                ) {
                    Text(
                        if (isInstalling) {
                            stringResource(R.string.marketplace_action_installing)
                        } else {
                            stringResource(R.string.marketplace_action_install)
                        },
                    )
                }
            }

            MarketplaceItemStatus.INSTALLED_INACTIVE -> {
                if (actions.onConfigure != null) {
                    Button(
                        onClick = actions.onConfigure,
                        modifier = Modifier.testTag("marketplace-configure-${item.id}"),
                    ) {
                        Text(stringResource(R.string.marketplace_action_configure))
                    }
                } else {
                    Text(
                        text = stringResource(R.string.marketplace_status_inactive),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }

            MarketplaceItemStatus.ACTIVE -> {
                Text(
                    text = stringResource(R.string.marketplace_status_active),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
@Suppress("FunctionName", "LongParameterList")
private fun MarketplaceExpandedDetails(
    item: MarketplaceItem,
    status: MarketplaceItemStatus,
    isInstalling: Boolean = false,
    onInstall: () -> Unit,
    onDisable: () -> Unit,
    onEnableSkill: () -> Unit,
    onUninstall: () -> Unit,
    onTagClick: (String) -> Unit,
) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    Text(
        text = stringResource(item.descriptionRes),
        style = MaterialTheme.typography.bodySmall,
    )
    item.authHintRes?.let { hint ->
        Text(
            text = stringResource(hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(vertical = 2.dp),
    ) {
        item.tags.forEach { tag ->
            Box(
                modifier =
                    Modifier
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
                        .clickable { onTagClick(tag) }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "#$tag",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    if (status != MarketplaceItemStatus.NOT_INSTALLED) {
        MarketplaceInstalledActionRow(
            item = item,
            status = status,
            isInstalling = isInstalling,
            onInstall = onInstall,
            onDisable = onDisable,
            onEnableSkill = onEnableSkill,
            onUninstall = onUninstall,
        )
    }
}

@Composable
@Suppress("FunctionName")
private fun MarketplaceInstalledActionRow(
    item: MarketplaceItem,
    status: MarketplaceItemStatus,
    isInstalling: Boolean = false,
    onInstall: () -> Unit,
    onDisable: () -> Unit,
    onEnableSkill: () -> Unit,
    onUninstall: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (status == MarketplaceItemStatus.ACTIVE) {
            OutlinedButton(
                onClick = onDisable,
                modifier = Modifier.weight(1f).testTag("marketplace-disable-${item.id}"),
            ) {
                Text(stringResource(R.string.marketplace_action_disable))
            }
        } else if (item.type == MarketplaceItemType.SKILL) {
            Button(
                onClick = onEnableSkill,
                modifier = Modifier.weight(1f).testTag("marketplace-enable-${item.id}"),
            ) {
                Text(stringResource(R.string.marketplace_action_enable))
            }
        }

        OutlinedButton(
            onClick = onInstall,
            enabled = !isInstalling,
            modifier = Modifier.weight(1f).testTag("marketplace-reinstall-${item.id}"),
        ) {
            Text(stringResource(R.string.marketplace_action_reinstall))
        }

        OutlinedButton(
            onClick = onUninstall,
            modifier = Modifier.weight(1f).testTag("marketplace-uninstall-${item.id}"),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Text(stringResource(R.string.marketplace_action_uninstall))
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun UninstallConfirmDialog(
    item: MarketplaceItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.marketplace_uninstall_title)) },
        text = {
            Text(stringResource(R.string.marketplace_uninstall_confirm, stringResource(item.nameRes)))
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.testTag("marketplace-uninstall-confirm"),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.marketplace_action_uninstall))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("marketplace-uninstall-cancel"),
            ) {
                Text(stringResource(R.string.session_export_close))
            }
        },
    )
}

@Composable
@Suppress("FunctionName")
private fun StatusBadge(status: MarketplaceItemStatus) {
    val (textRes, bg, fg) =
        when (status) {
            MarketplaceItemStatus.NOT_INSTALLED -> {
                Triple(
                    R.string.marketplace_status_not_installed,
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            MarketplaceItemStatus.INSTALLED_INACTIVE -> {
                Triple(
                    R.string.marketplace_status_inactive,
                    MaterialTheme.colorScheme.secondaryContainer,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            MarketplaceItemStatus.ACTIVE -> {
                Triple(
                    R.string.marketplace_status_active,
                    MaterialTheme.colorScheme.primaryContainer,
                    MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

    Box(
        modifier =
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(bg)
                .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.labelSmall,
            color = fg,
        )
    }
}

@Composable
@Suppress("FunctionName")
private fun TypeTag(type: MarketplaceItemType) {
    val labelRes =
        when (type) {
            MarketplaceItemType.CONNECTOR -> R.string.marketplace_filter_connector
            MarketplaceItemType.MCP -> R.string.marketplace_filter_mcp
            MarketplaceItemType.SKILL -> R.string.marketplace_filter_skill
        }
    Box(
        modifier =
            Modifier
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
@Suppress("FunctionName")
private fun AuthTag(auth: MarketplaceAuthRequirement) {
    val labelRes =
        when (auth) {
            MarketplaceAuthRequirement.NONE -> R.string.marketplace_auth_none
            MarketplaceAuthRequirement.BEARER_TOKEN -> R.string.marketplace_auth_bearer
            MarketplaceAuthRequirement.API_KEY -> R.string.marketplace_auth_api_key
            MarketplaceAuthRequirement.LOCAL -> R.string.marketplace_auth_local
        }
    Box(
        modifier =
            Modifier
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}
