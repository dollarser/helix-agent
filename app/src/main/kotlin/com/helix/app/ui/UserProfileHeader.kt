package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.account.LocalUserProfile

@Composable
@Suppress("FunctionName")
internal fun UserProfileHeader(
    profile: LocalUserProfile,
    onEdit: () -> Unit,
) {
    Surface(onClick = onEdit, color = Color.Transparent, modifier = Modifier.testTag("profile-edit")) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LocalProfileAvatar(profile.avatarFile, 76.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    profile.displayName ?: stringResource(R.string.user_profile_default_name),
                    Modifier.testTag("profile-display-name"),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    stringResource(R.string.user_profile_edit),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DrawerChevron()
        }
    }
}
