@file:Suppress("FunctionName", "ktlint:standard:function-naming", "LongMethod", "LongParameterList")

package com.helix.feature.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.helix.feature.browser.BrowserTab
import com.helix.feature.browser.R
import com.helix.feature.browser.storage.BrowserPreferences

/**
 * Feature-rich menu sheet inspired by Via and X Browser:
 * - Bookmarks, History, Downloads
 * - Desktop UA, Night Mode, No-Image, AdBlock toggles
 * - Find in Page, Eruda Console, View Source, Reader Mode
 * - Settings & Clear Cache / Cookies / History
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowserMenuSheet(
    selectedTab: BrowserTab?,
    preferences: BrowserPreferences,
    isBookmarked: Boolean,
    onToggleBookmark: () -> Unit,
    onCopyUrl: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDownloads: () -> Unit,
    onStartFindInPage: () -> Unit,
    onToggleDesktopMode: () -> Unit,
    onToggleNightMode: () -> Unit,
    onToggleNoImageMode: () -> Unit,
    onToggleAdBlock: () -> Unit,
    onOpenUserScripts: () -> Unit,
    onOpenDevTools: () -> Unit,
    onViewSource: () -> Unit,
    onReaderMode: () -> Unit,
    onClearCookies: () -> Unit,
    onClearCache: () -> Unit,
    onClearHistory: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
        ) {
            // Drag Handle / Close Row
            Box(
                modifier =
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Quick Top Action Bar: Bookmark, Copy, Dismiss
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QuickTopAction(
                    icon = if (isBookmarked) "★" else "☆",
                    label = if (isBookmarked) "已加书签" else "加书签",
                    onClick = {
                        onToggleBookmark()
                        onDismiss()
                    },
                )
                QuickTopAction(
                    icon = "🔗",
                    label = stringResource(R.string.browser_menu_copy_url),
                    onClick = {
                        onCopyUrl()
                        onDismiss()
                    },
                )
                QuickTopAction(
                    icon = "✕",
                    label = stringResource(R.string.browser_dismiss),
                    onClick = onDismiss,
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Features Grid
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                maxItemsInEachRow = 4,
            ) {
                // Bookmarks
                MenuItem(
                    icon = "⭐",
                    label = stringResource(R.string.browser_menu_bookmarks),
                    onClick = {
                        onOpenBookmarks()
                        onDismiss()
                    },
                )

                // History
                MenuItem(
                    icon = "🕒",
                    label = stringResource(R.string.browser_menu_history),
                    onClick = {
                        onOpenHistory()
                        onDismiss()
                    },
                )

                // Downloads
                MenuItem(
                    icon = "⬇️",
                    label = stringResource(R.string.browser_menu_downloads),
                    onClick = {
                        onOpenDownloads()
                        onDismiss()
                    },
                )

                // Find in page
                MenuItem(
                    icon = "🔍",
                    label = stringResource(R.string.browser_menu_find_in_page),
                    onClick = {
                        onStartFindInPage()
                        onDismiss()
                    },
                )

                // Desktop UA toggle
                val isDesktop = selectedTab?.isDesktopMode == true || preferences.desktopMode
                MenuItem(
                    icon = "🖥️",
                    label = stringResource(R.string.browser_menu_desktop_site),
                    isActive = isDesktop,
                    onClick = onToggleDesktopMode,
                )

                // Night mode toggle
                MenuItem(
                    icon = "🌙",
                    label = stringResource(R.string.browser_menu_night_mode),
                    isActive = preferences.nightMode,
                    onClick = onToggleNightMode,
                )

                // No-Image mode toggle
                MenuItem(
                    icon = "🖼️",
                    label = stringResource(R.string.browser_menu_no_image),
                    isActive = preferences.noImageMode,
                    onClick = onToggleNoImageMode,
                )

                // AdBlock toggle
                MenuItem(
                    icon = "🛡️",
                    label = stringResource(R.string.browser_menu_adblock),
                    isActive = preferences.adBlockEnabled,
                    onClick = onToggleAdBlock,
                )

                // User Scripts
                MenuItem(
                    icon = "📜",
                    label = stringResource(R.string.browser_menu_userscripts),
                    onClick = {
                        onOpenUserScripts()
                        onDismiss()
                    },
                )

                // DevTools / Eruda
                MenuItem(
                    icon = "🛠️",
                    label = stringResource(R.string.browser_menu_devtools),
                    onClick = {
                        onOpenDevTools()
                        onDismiss()
                    },
                )

                // View Source
                MenuItem(
                    icon = "📄",
                    label = stringResource(R.string.browser_menu_view_source),
                    onClick = {
                        onViewSource()
                        onDismiss()
                    },
                )

                // Reader Mode
                MenuItem(
                    icon = "📖",
                    label = stringResource(R.string.browser_menu_reader_mode),
                    onClick = {
                        onReaderMode()
                        onDismiss()
                    },
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(modifier = Modifier.height(12.dp))

            // Clear Data Row (Preserves existing testTags:
            // browser-clear-cookies, browser-clear-cache, browser-clear-history)
            Text(
                text = stringResource(R.string.browser_menu_settings),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    onClick = onClearCookies,
                    modifier = Modifier.testTag("browser-clear-cookies"),
                ) {
                    Text(stringResource(R.string.browser_clear_cookies), fontSize = 12.sp)
                }
                TextButton(
                    onClick = onClearCache,
                    modifier = Modifier.testTag("browser-clear-cache"),
                ) {
                    Text(stringResource(R.string.browser_clear_cache), fontSize = 12.sp)
                }
                TextButton(
                    onClick = onClearHistory,
                    modifier = Modifier.testTag("browser-clear-history"),
                ) {
                    Text(stringResource(R.string.browser_clear_history), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun QuickTopAction(
    icon: String,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = icon, fontSize = 14.sp)
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MenuItem(
    icon: String,
    label: String,
    isActive: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = if (isActive) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val textColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface

    Column(
        modifier =
            Modifier
                .width(74.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = icon, fontSize = 22.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
