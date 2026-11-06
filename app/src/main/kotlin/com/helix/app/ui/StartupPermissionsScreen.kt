package com.helix.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R

@Composable
@Suppress("FunctionName")
internal fun StartupPermissionsScreen(onContinue: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(16.dp)
            .testTag("startup-permissions"),
    ) {
        Text(stringResource(R.string.startup_permissions_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.startup_permissions_help), style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.weight(1f)) { SystemPermissionsScreen(essentialsOnly = true) }
        Button(onContinue, Modifier.fillMaxWidth().testTag("startup-permissions-continue")) {
            Text(stringResource(R.string.startup_permissions_continue))
        }
    }
}
