package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R

@Composable
@Suppress("FunctionName")
internal fun ArtifactsRefresh(onRefresh: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("artifacts-header"),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onRefresh, modifier = Modifier.testTag("artifacts-refresh")) {
            Text(stringResource(R.string.cap_refresh))
        }
    }
}
