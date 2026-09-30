package com.helix.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    Column(Modifier.padding(12.dp).testTag("drawer-profile")) {
        Text(
            stringResource(
                if (profile ==
                    SafetyProfile.ADVANCED
                ) {
                    R.string.settings_current_advanced
                } else {
                    R.string.settings_current_standard
                },
            ),
        )
        if (AdvancedProfileAvailability.ADVANCED_AVAILABLE) {
            TextButton(
                onClick = {
                    if (profile == SafetyProfile.STANDARD) confirm = true else store.switchTo(SafetyProfile.STANDARD)
                },
                modifier = Modifier.testTag("drawer-profile-switch"),
            ) {
                Text(
                    stringResource(
                        if (profile ==
                            SafetyProfile.STANDARD
                        ) {
                            R.string.settings_switch_to_advanced
                        } else {
                            R.string.settings_switch_back_standard
                        },
                    ),
                )
            }
        }
    }
    AdvancedRiskDialog(confirm, {
        store.switchTo(SafetyProfile.ADVANCED)
        confirm = false
    }, { confirm = false })
}
