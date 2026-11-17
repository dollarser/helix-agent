package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.helix.app.R
import com.helix.ui.shared.R as SharedR

/** A full-screen destination retaining the caller's navigation and draft underneath. */
@Composable
@Suppress("FunctionName")
internal fun UserPageFrame(
    title: String,
    tag: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        UserPageSystemBars()
        // Paint behind system bars; inset content only, rather than revealing the dimmed caller.
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .imePadding()
                    .testTag(tag),
                topBar = {
                    androidx.compose.foundation.layout.Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onBack,
                            Modifier.testTag("$tag-back"),
                        ) { Icon(painterResource(R.drawable.ic_user_back), stringResource(SharedR.string.common_back)) }
                        Text(title, style = MaterialTheme.typography.titleLarge)
                    }
                },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), content = content)
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun UserPageSystemBars() {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window
    val lightBackground = MaterialTheme.colorScheme.background.luminance() > 0.5f
    SideEffect {
        window?.let {
            WindowCompat.getInsetsController(it, view).apply {
                isAppearanceLightStatusBars = lightBackground
                isAppearanceLightNavigationBars = lightBackground
            }
        }
    }
}
