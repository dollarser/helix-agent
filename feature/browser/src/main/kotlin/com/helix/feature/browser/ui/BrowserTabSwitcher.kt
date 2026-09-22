@file:Suppress("FunctionName", "ktlint:standard:function-naming", "LongMethod")

package com.helix.feature.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.helix.feature.browser.BrowserTab
import com.helix.feature.browser.BrowserTabController
import com.helix.feature.browser.R

/**
 * Visual card-grid Tab Switcher inspired by Via and X Browser:
 * - Card preview of each open tab with active indicator
 * - New tab (+) with testTag "browser-tab-new"
 * - Close tab (×) with testTag "browser-tab-close-${id}"
 * - Card selection with testTag "browser-tab-${id}"
 * - Incognito (private browsing) toggle
 * - Close all tabs action
 */
@Composable
fun BrowserTabSwitcher(
    state: BrowserTabController.State,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: (isIncognito: Boolean) -> Unit,
    onCloseAllTabs: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Bar
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.browser_tab_switcher_title) + " (${state.tabs.size})",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Close All Button
                    if (state.tabs.isNotEmpty()) {
                        TextButton(onClick = onCloseAllTabs) {
                            Text(
                                text = stringResource(R.string.browser_tab_close_all),
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 13.sp,
                            )
                        }
                    }

                    // Done / Back button
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = stringResource(R.string.browser_dismiss),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            // Tabs Grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.tabs, key = { it.id }) { tab ->
                    TabCard(
                        tab = tab,
                        isSelected = tab.id == state.selectedId,
                        onClick = {
                            onSelectTab(tab.id)
                            onDismiss()
                        },
                        onClose = { onCloseTab(tab.id) },
                    )
                }
            }

            // Bottom Action Bar inside Tab Switcher
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 4.dp,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Incognito New Tab
                    TextButton(enabled = state.tabs.size < BrowserTabController.DEFAULT_MAX_TABS, onClick = {
                        onNewTab(true)
                        onDismiss()
                    }) {
                        Text(
                            text = "🕶️ " + stringResource(R.string.browser_tab_incognito),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    // Standard New Tab (+) with testTag
                    Box(
                        modifier =
                            Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable(enabled = state.tabs.size < BrowserTabController.DEFAULT_MAX_TABS) {
                                    onNewTab(false)
                                    onDismiss()
                                }.testTag("browser-tab-new"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "＋",
                            fontSize = 20.sp,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabCard(
    tab: BrowserTab,
    isSelected: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    val cardBackground =
        if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        }

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .border(width = 2.dp, color = borderColor, shape = RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .testTag("browser-tab-${tab.id}"),
        shape = RoundedCornerShape(12.dp),
        color = cardBackground,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Card Title Header
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (tab.isIncognito) {
                    Text(text = "🕶️", fontSize = 11.sp, modifier = Modifier.padding(end = 4.dp))
                }
                Text(
                    text = tab.label ?: stringResource(R.string.browser_new_tab),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                // Close Button with testTag
                Box(
                    modifier =
                        Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .clickable { onClose() }
                            .testTag("browser-tab-close-${tab.id}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "×",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Card Body (Preview Placeholder)
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text =
                        if (tab.url == BrowserTabController.ABOUT_BLANK) {
                            "新标签页"
                        } else {
                            tab.url
                        },
                    fontSize = 11.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
    }
}
