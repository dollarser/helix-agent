package com.helix.app.marketplace

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var statusMap by remember { mutableStateOf<Map<String, MarketplaceItemStatus>>(emptyMap()) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var isActionError by remember { mutableStateOf(false) }
    var installingId by remember { mutableStateOf<String?>(null) }

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

        // Filter chips
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            FilterChip(
                selected = selectedFilter == null,
                onClick = { selectedFilter = null },
                label = { Text(stringResource(R.string.marketplace_filter_all)) },
                modifier = Modifier.testTag("marketplace-filter-all"),
            )
            FilterChip(
                selected = selectedFilter == MarketplaceItemType.CONNECTOR,
                onClick = { selectedFilter = MarketplaceItemType.CONNECTOR },
                label = { Text(stringResource(R.string.marketplace_filter_connector)) },
                modifier = Modifier.testTag("marketplace-filter-connector"),
            )
            FilterChip(
                selected = selectedFilter == MarketplaceItemType.MCP,
                onClick = { selectedFilter = MarketplaceItemType.MCP },
                label = { Text(stringResource(R.string.marketplace_filter_mcp)) },
                modifier = Modifier.testTag("marketplace-filter-mcp"),
            )
            FilterChip(
                selected = selectedFilter == MarketplaceItemType.SKILL,
                onClick = { selectedFilter = MarketplaceItemType.SKILL },
                label = { Text(stringResource(R.string.marketplace_filter_skill)) },
                modifier = Modifier.testTag("marketplace-filter-skill"),
            )
        }

        actionMessage?.let { msg ->
            Text(
                text = msg,
                color = if (isActionError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        val items =
            service.items().filter {
                selectedFilter == null || it.type == selectedFilter
            }

        val successMsg = stringResource(R.string.marketplace_install_success)
        val failMsg = stringResource(R.string.marketplace_install_failed)

        items.forEach { item ->
            val status = statusMap[item.id] ?: MarketplaceItemStatus.NOT_INSTALLED
            MarketplaceItemCard(
                item = item,
                status = status,
                isInstalling = installingId == item.id,
                onInstall = {
                    if (installingId != null) return@MarketplaceItemCard
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
            )
        }
    }
}

@Composable
@Suppress("FunctionName", "LongMethod")
private fun MarketplaceItemCard(
    item: MarketplaceItem,
    status: MarketplaceItemStatus,
    isInstalling: Boolean = false,
    onInstall: () -> Unit,
    onConfigure: (() -> Unit)? = null,
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
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { expanded = !expanded },
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
                            onClick = onInstall,
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
                        if (onConfigure != null) {
                            Button(onClick = onConfigure) {
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
    }
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
