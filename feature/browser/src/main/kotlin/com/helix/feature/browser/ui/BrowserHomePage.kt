@file:Suppress("FunctionName", "ktlint:standard:function-naming", "LongMethod", "LongParameterList")

package com.helix.feature.browser.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.helix.feature.browser.R
import com.helix.feature.browser.engine.SearchEngine
import com.helix.feature.browser.engine.SearchEngines
import com.helix.feature.browser.storage.SpeedDial

/**
 * Via-inspired minimalist start page:
 * - Helix logo & greeting
 * - Centered search engine selector & search bar
 * - Speed Dial shortcut grid
 * - Blocked ads counter chip
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowserHomePage(
    speedDials: List<SpeedDial>,
    currentSearchEngine: SearchEngine,
    blockedAdsCount: Long,
    onSearchEngineSelect: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onAddSpeedDial: (String, String) -> Unit,
    onRemoveSpeedDial: (String) -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var engineMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        // Minimalist Logo & Greeting
        Text(
            text = "HELIX",
            fontSize = 32.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 4.sp,
            color = MaterialTheme.colorScheme.primary,
        )

        Text(
            text = stringResource(R.string.browser_home_greeting),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Center Search Box with Engine Switcher
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable { onSearchClick() },
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Search Engine Switcher Icon
                Box {
                    Surface(
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { engineMenuExpanded = true },
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = currentSearchEngine.name,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }

                    DropdownMenu(
                        expanded = engineMenuExpanded,
                        onDismissRequest = { engineMenuExpanded = false },
                    ) {
                        SearchEngines.ALL.forEach { engine ->
                            DropdownMenuItem(
                                text = { Text(engine.name) },
                                onClick = {
                                    onSearchEngineSelect(engine.id)
                                    engineMenuExpanded = false
                                },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = stringResource(R.string.browser_search_or_type_url),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.weight(1f),
                )

                Text(
                    text = "🔍",
                    fontSize = 16.sp,
                )
            }
        }

        Spacer(modifier = Modifier.height(36.dp))

        // Speed Dial / Quick Navigation Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.browser_quick_access),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = "＋ " + stringResource(R.string.browser_add_shortcut),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showAddDialog = true }
                        .padding(4.dp),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Speed Dial Grid (FlowRow)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            maxItemsInEachRow = 4,
        ) {
            speedDials.forEach { dial ->
                SpeedDialItem(
                    dial = dial,
                    onClick = { onOpenUrl(dial.url) },
                    onLongClick = { onRemoveSpeedDial(dial.id) },
                )
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        // AdBlock Statistics Badge
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "🛡️", fontSize = 13.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.browser_ads_blocked_count, blockedAdsCount),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }

    if (showAddDialog) {
        AddSpeedDialDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { title, url ->
                onAddSpeedDial(title, url)
                showAddDialog = false
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SpeedDialItem(
    dial: SpeedDial,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .width(68.dp)
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.size(52.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            ) {
                Text(
                    text = dial.iconText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Text(
            text = dial.title,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun AddSpeedDialDialog(
    onDismiss: () -> Unit,
    onAdd: (title: String, url: String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.browser_add_shortcut)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("网址 (如 https://...)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (url.isNotBlank()) {
                        val finalUrl = if (!url.contains("://")) "https://$url" else url
                        val finalTitle = title.ifBlank { url }
                        onAdd(finalTitle, finalUrl)
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
