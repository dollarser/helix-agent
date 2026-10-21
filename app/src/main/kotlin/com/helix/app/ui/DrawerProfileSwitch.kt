package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.profile.AdvancedProfileAvailability
import com.helix.app.profile.SafetyProfileStore
import com.helix.core.model.SafetyProfile

@Composable
@Suppress("FunctionName")
internal fun DrawerProfileSwitch(store: SafetyProfileStore) {
    val profile by store.flow.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp).testTag("drawer-profile"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            stringResource(R.string.drawer_permission_mode),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val label = if (profile == SafetyProfile.ADVANCED) "Advanced" else "Standard"
        if (AdvancedProfileAvailability.ADVANCED_AVAILABLE) {
            TextButton(
                onClick = {
                    if (profile == SafetyProfile.STANDARD) confirm = true else store.switchTo(SafetyProfile.STANDARD)
                },
                modifier = Modifier.testTag("drawer-profile-switch"),
            ) {
                Text(label, style = MaterialTheme.typography.bodySmall)
                DrawerChevron()
            }
        } else {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    AdvancedRiskDialog(confirm, {
        store.switchTo(SafetyProfile.ADVANCED)
        confirm = false
    }, { confirm = false })
}
