@file:Suppress("FunctionName", "ktlint:standard:function-naming", "LongMethod", "LongParameterList")

package com.helix.feature.browser.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.helix.feature.browser.BrowserTab
import com.helix.feature.browser.BrowserTabController
import com.helix.feature.browser.R

/**
 * Modern Omnibox / TopBar inspired by Via and X Browser:
 * - Security indicator (SSL lock)
 * - Clean rounded URL/Search input
 * - Clear button (✕) and Reload/Stop action
 * - Smooth animated progress indicator at the bottom edge
 */
@Composable
@Suppress("CyclomaticComplexMethod") // Declarative editing, loading and connection states of one address bar.
fun BrowserTopBar(
    selectedTab: BrowserTab?,
    urlText: String,
    onUrlChange: (String) -> Unit,
    onSubmitUrl: () -> Unit,
    onReload: () -> Unit,
    onStop: () -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val homeLabel = stringResource(R.string.browser_home)
    val clearLabel = stringResource(R.string.browser_clear_address)
    val reloadLabel = stringResource(R.string.browser_reload)
    val stopLabel = stringResource(R.string.browser_stop)
    val goLabel = stringResource(R.string.browser_go)
    val submit = {
        onSubmitUrl()
        focus.clearFocus()
        keyboard?.hide()
        Unit
    }
    val isLoading = selectedTab?.isLoading == true
    val progress = selectedTab?.progress ?: 0
    val isBlank = selectedTab == null || selectedTab.url == BrowserTabController.ABOUT_BLANK
    val isEditing = urlText != selectedTab?.url && !(isBlank && urlText.isEmpty())
    val isHttps =
        selectedTab?.url?.startsWith("https://", ignoreCase = true) == true &&
            selectedTab.error == null && !isEditing && !isLoading

    val animatedProgress by animateFloatAsState(
        targetValue = if (isLoading) (progress.coerceIn(10, 100) / 100f) else 0f,
        label = "browser_progress",
    )

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Home / Logo Icon
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .semantics { contentDescription = homeLabel }
                        .clickable(role = Role.Button) { onHome() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "⌂",
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Rounded Omnibox Container
            Surface(
                modifier =
                    Modifier
                        .weight(1f)
                        .height(48.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Security Lock indicator
                    if (isHttps) {
                        Text(
                            text = "🔒",
                            fontSize = 12.sp,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }

                    // Input Field (testTag: browser-url-field for test suite compatibility)
                    Box(modifier = Modifier.weight(1f)) {
                        if (urlText.isEmpty()) {
                            Text(
                                text = stringResource(R.string.browser_search_or_type_url),
                                style =
                                    TextStyle(
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BasicTextField(
                            value = urlText,
                            onValueChange = onUrlChange,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .testTag("browser-url-field"),
                            singleLine = true,
                            textStyle =
                                TextStyle(
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions =
                                KeyboardOptions(
                                    imeAction = ImeAction.Go,
                                    keyboardType = KeyboardType.Uri,
                                ),
                            keyboardActions =
                                KeyboardActions(
                                    onGo = { submit() },
                                    onDone = { submit() },
                                ),
                        )
                    }

                    // Clear button
                    if (urlText.isNotEmpty()) {
                        Box(
                            modifier =
                                Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .semantics { contentDescription = clearLabel }
                                    .clickable(role = Role.Button) { onUrlChange("") },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "✕",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Stop or Reload button
            if (isLoading && !isEditing) {
                Box(
                    modifier =
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .semantics { contentDescription = stopLabel }
                            .clickable(role = Role.Button) { onStop() }
                            .testTag("browser-stop"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "✕",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                }
            } else if (!isBlank && !isEditing) {
                Box(
                    modifier =
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .semantics { contentDescription = reloadLabel }
                            .clickable(role = Role.Button) { onReload() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "⟳",
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                    )
                }
            } else {
                Box(
                    modifier =
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .semantics { contentDescription = goLabel }
                            .clickable(enabled = urlText.isNotBlank(), role = Role.Button) { submit() }
                            .testTag("browser-go"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "➔",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        // Slim Animated Loading Progress Indicator
        AnimatedVisibility(visible = isLoading && animatedProgress > 0f) {
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
            )
        }
    }
}
