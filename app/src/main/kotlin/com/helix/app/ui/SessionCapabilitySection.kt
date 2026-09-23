package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.connector.SessionCapabilityUiState
import com.helix.app.connector.SessionConnectorItemUi

/**
 * HXA-129 Session capability panel (ADR-CONNECTORS-003 §2).
 * Allows user to enable or disable installed Connectors independently for this session.
 */
@Composable
@Suppress("FunctionName")
fun SessionCapabilitySection(
    state: SessionCapabilityUiState,
    onToggleConnector: (connectorId: String, enabled: Boolean) -> Unit,
    onConfigureConnector: (connectorId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag("session-capability-section"),
    ) {
        Text(
            text = stringResource(R.string.session_capability_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.session_capability_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))

        val enabledCount = state.connectors.count { it.isEnabledInSession }
        Text(
            text = stringResource(R.string.session_capability_enabled_count, enabledCount, state.connectors.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (state.connectors.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Text(
                    text = stringResource(R.string.session_capability_empty),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.connectors, key = { it.connectorId }) { connector ->
                    SessionConnectorRow(
                        item = connector,
                        onToggle = { onToggleConnector(connector.connectorId, it) },
                        onConfigure = { onConfigureConnector(connector.connectorId) },
                    )
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "LongMethod")
private fun SessionConnectorRow(
    item: SessionConnectorItemUi,
    onToggle: (Boolean) -> Unit,
    onConfigure: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("session-capability-item-${item.connectorId}"),
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (item.isEnabledInSession) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLowest
                    },
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${item.packageId} · v${item.version}${item.author?.let { " · $it" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = item.isEnabledInSession,
                    onCheckedChange = onToggle,
                    modifier = Modifier.testTag("session-capability-toggle-${item.connectorId}"),
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.isReady) {
                        SuggestionChip(
                            onClick = {},
                            label = { Text(stringResource(R.string.session_capability_status_ready)) },
                            colors =
                                SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                        )
                    } else {
                        SuggestionChip(
                            onClick = onConfigure,
                            label = { Text(stringResource(R.string.session_capability_status_needs_config)) },
                            colors =
                                SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    labelColor = MaterialTheme.colorScheme.onErrorContainer,
                                ),
                        )
                    }

                    if (item.hasSharedRemainingSource) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.session_capability_remaining_source_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }

                if (!item.isReady) {
                    OutlinedButton(
                        onClick = onConfigure,
                        modifier = Modifier.testTag("session-capability-repair-${item.connectorId}"),
                    ) {
                        Text(stringResource(R.string.session_capability_repair_button))
                    }
                }
            }
        }
    }
}
