@file:Suppress("FunctionName", "ktlint:standard:function-naming", "LongMethod", "LongParameterList")

package com.helix.feature.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.helix.feature.browser.BrowserTab

/**
 * Classic 5-button bottom navigation bar inspired by Via and X Browser:
 * [Back]  [Forward]  [Home]  [Tabs]  [Menu]
 */
@Composable
fun BrowserBottomBar(
    selectedTab: BrowserTab?,
    tabCount: Int,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onHome: () -> Unit,
    onTabsClick: () -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canGoBack = selectedTab != null && selectedTab.canGoBack
    val canGoForward = selectedTab != null && selectedTab.canGoForward

    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .height(52.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 1. Back button
            BottomBarItem(
                text = "◀",
                enabled = canGoBack,
                onClick = onBack,
                testTag = "browser-back",
            )

            // 2. Forward button
            BottomBarItem(
                text = "▶",
                enabled = canGoForward,
                onClick = onForward,
                testTag = "browser-forward",
            )

            // 3. Home button
            BottomBarItem(
                text = "⌂",
                enabled = true,
                onClick = onHome,
            )

            // 4. Tabs switcher button with badge count
            Box(
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable { onTabsClick() }
                        .testTag("browser-tabs"),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(24.dp)
                            .border(
                                width = 1.5.dp,
                                color = MaterialTheme.colorScheme.onSurface,
                                shape = RoundedCornerShape(6.dp),
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$tabCount",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // 5. Menu button (☰)
            BottomBarItem(
                text = "☰",
                enabled = true,
                onClick = onMenuClick,
                testTag = "browser-menu",
            )
        }
    }
}

@Composable
private fun BottomBarItem(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    val alpha = if (enabled) 1f else 0.3f
    val contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)

    Box(
        modifier =
            modifier
                .size(40.dp)
                .clip(CircleShape)
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
                .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
        )
    }
}
