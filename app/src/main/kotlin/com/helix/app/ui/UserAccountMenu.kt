package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R

/** Overview contains destinations, not expanded account data. */
@Composable
@Suppress("FunctionName")
internal fun UserAccountMenu(
    unread: Int?,
    onNotices: () -> Unit,
    onUsage: () -> Unit,
    onAccount: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(horizontal = 8.dp)) {
            UserAccountMenuRow(
                R.string.account_notices_title,
                R.drawable.ic_drawer_bell,
                "profile-notices",
                onNotices,
            ) {
                if (unread != null && unread > 0) Badge { Text(if (unread > 99) "99+" else unread.toString()) }
            }
            HorizontalDivider(Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outlineVariant)
            UserAccountMenuRow(R.string.account_usage_title, R.drawable.ic_drawer_models, "profile-usage", onUsage)
            HorizontalDivider(Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outlineVariant)
            UserAccountMenuRow(
                R.string.user_profile_account_section,
                R.drawable.ic_permission_shield,
                "profile-account",
                onAccount,
            )
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun UserAccountMenuRow(
    title: Int,
    icon: Int,
    tag: String,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Surface(onClick, Modifier.fillMaxWidth().testTag(tag), color = Color.Transparent) {
        Row(
            Modifier.heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(painterResource(icon), null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(title), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            trailing()
            DrawerChevron()
        }
    }
}
