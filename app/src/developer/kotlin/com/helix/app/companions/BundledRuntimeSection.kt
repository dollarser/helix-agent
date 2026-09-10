package com.helix.app.companions

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.ui.SettingsActions

@Composable
@Suppress("FunctionName")
internal fun BundledRuntimeSection() {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.bundled_runtime_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.bundled_runtime_note), style = MaterialTheme.typography.bodySmall)
        SettingsActions {
            val entries = listOf("subscriptions" to R.string.bundled_subscriptions, "proot" to R.string.bundled_proot)
            entries.forEach { (id, label) ->
                OutlinedButton({
                    context.startActivity(
                        Intent(context, BundledRuntimeInstallActivity::class.java).putExtra("runtime", id),
                    )
                }) { Text(stringResource(label)) }
            }
        }
    }
}
