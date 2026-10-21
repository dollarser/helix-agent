package com.helix.app.ui

import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal val LocalNavigationIsPermanent = staticCompositionLocalOf { false }

/** Width is a layout choice, never a capability/channel switch. Short landscape windows keep their content. */
internal fun permanentNavigation(
    widthDp: Int,
    heightDp: Int,
    fontScale: Float,
): Boolean = widthDp >= 1000 && heightDp >= 480 && widthDp / fontScale.coerceAtLeast(1f) >= 600

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionName")
internal fun AdaptiveHelixNavigation(
    drawerState: DrawerState,
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    val width = with(density) { window.width.toDp() }
    val height = with(density) { window.height.toDp() }
    val permanent =
        permanentNavigation(width.value.toInt(), height.value.toInt(), density.fontScale)
    LaunchedEffect(permanent) { if (permanent) drawerState.close() }
    CompositionLocalProvider(LocalNavigationIsPermanent provides permanent) {
        if (permanent) {
            PermanentNavigationDrawer(drawerContent = {
                DrawerVisualTheme {
                    PermanentDrawerSheet(Modifier.width(254.dp).testTag("drawer-panel")) { drawerContent() }
                }
            }, content = content)
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                gesturesEnabled = drawerState.isOpen,
                drawerContent = {
                    DrawerVisualTheme {
                        ModalDrawerSheet(
                            modifier =
                                Modifier
                                    .width(
                                        (width * 0.86f).coerceAtMost(330.dp),
                                    ).testTag("drawer-panel"),
                        ) { drawerContent() }
                    }
                },
                content = content,
            )
        }
    }
}
