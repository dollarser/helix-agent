package com.helix.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.account.AccountStatus

internal enum class AccountEntryPresentation { DRAWER, MODEL_PICKER, SETTINGS }

/** Display-only account state; never a credential or a model authorization decision. */
@Composable
@Suppress("FunctionName")
internal fun AccountEntryButton(
    status: AccountStatus,
    presentation: AccountEntryPresentation,
    unread: Int = 0,
    profile: com.helix.app.account.LocalUserProfile =
        com.helix.app.account
            .LocalUserProfile(),
    displayName: String? = null,
    onClick: () -> Unit,
) {
    val title =
        stringResource(
            when (status) {
                AccountStatus.SIGNED_IN -> R.string.account_signed_in
                AccountStatus.REAUTH_REQUIRED -> R.string.account_reauth
                AccountStatus.UNAVAILABLE -> R.string.account_unavailable
                AccountStatus.SIGNED_OUT -> R.string.account_drawer_login
            },
        )
    val subtitle =
        stringResource(
            if (status == AccountStatus.SIGNED_IN) R.string.account_manage else R.string.account_model_access,
        )
    if (presentation == AccountEntryPresentation.DRAWER) {
        DrawerAccountHeader(displayName?.takeIf { it.isNotBlank() } ?: title, subtitle, unread, profile, onClick)
    } else if (presentation == AccountEntryPresentation.SETTINGS) {
        SettingsEntryRow(title, R.drawable.ic_drawer_user, onClick, Modifier.testTag("account-entry"), subtitle)
    } else {
        TextButton(onClick, Modifier.fillMaxWidth().testTag("account-entry")) {
            Text(
                if (presentation == AccountEntryPresentation.MODEL_PICKER &&
                    status == AccountStatus.SIGNED_OUT
                ) {
                    subtitle
                } else {
                    title
                },
            )
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun DrawerAccountHeader(
    title: String,
    subtitle: String,
    unread: Int,
    profile: com.helix.app.account.LocalUserProfile,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.fillMaxWidth().testTag("drawer-account"),
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LocalProfileAvatar(profile.avatarFile, 40.dp, Modifier.testTag("drawer-account-avatar"))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (unread > 0) {
                androidx.compose.material3.Badge(Modifier.testTag("account-unread-badge")) {
                    Text(if (unread > 99) "99+" else unread.toString())
                }
            }
            DrawerChevron()
        }
    }
}
