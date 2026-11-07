package com.helix.app

import androidx.compose.runtime.Composable
import com.helix.app.maintenance.PreviewResetRequest
import com.helix.app.ui.FirstLaunchNoticeScreen
import com.helix.app.ui.StartupPermissionsScreen
import com.helix.ui.shared.HelixTheme

/** Error/reset review always wins; readiness never interrupts notice or permission review. */
@Composable
@Suppress("FunctionName", "LongParameterList")
internal fun StartupExperience(
    state: AppStartupState,
    onboarding: StartupOnboarding?,
    contentReady: Boolean,
    onNotice: () -> Unit,
    onPermissionsDone: () -> Unit,
    content: @Composable () -> Unit,
    onConfirmReset: (PreviewResetRequest) -> Unit,
    onExit: () -> Unit,
    onRecheck: () -> Unit,
) {
    val blocked =
        state is AppStartupState.ResetRequired || state == AppStartupState.Failed ||
            state == AppStartupState.RestoreFailed
    HelixTheme {
        when {
            blocked -> {
                AppStartupScreen(state, onConfirmReset, onExit, onRecheck)
            }

            onboarding == StartupOnboarding.NOTICE -> {
                FirstLaunchNoticeScreen(onNotice)
            }

            state == AppStartupState.Loading || onboarding == null -> {
                AppStartupScreen(
                    state,
                    onConfirmReset,
                    onExit,
                    onRecheck,
                )
            }

            onboarding == StartupOnboarding.PERMISSIONS -> {
                StartupPermissionsScreen(onPermissionsDone)
            }

            contentReady -> {
                content()
            }

            else -> {
                AppStartupScreen(state, onConfirmReset, onExit, onRecheck)
            }
        }
    }
}
