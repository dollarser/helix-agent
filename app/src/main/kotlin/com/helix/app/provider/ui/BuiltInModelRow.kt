package com.helix.app.provider.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.provider.BuiltInModel
import com.helix.app.provider.ConnectionTestMapping
import com.helix.app.provider.ConnectionTestStatus
import com.helix.app.provider.ProviderRowUi

@Composable
@Suppress("FunctionName")
internal fun BuiltInModelRow(
    row: ProviderRowUi,
    testing: Boolean,
    actions: ProviderRowActions,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("builtin-model-row"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.builtin_model_title), style = MaterialTheme.typography.titleMedium)
        Text(row.model, style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.builtin_model_description), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.builtin_model_disclosure), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.builtin_model_shared_limits), style = MaterialTheme.typography.bodySmall)
        val failure = row.modelGenerations[row.model]?.failure
        val status =
            if (failure != null) {
                BuiltInModel.errorLabel(failure.name) ?: ConnectionTestMapping.codeLabel(failure)
            } else {
                when (val result = row.status) {
                    ConnectionTestStatus.Untested -> {
                        R.string.builtin_model_unverified
                    }

                    is ConnectionTestStatus.Passed -> {
                        R.string.builtin_model_verified
                    }

                    is ConnectionTestStatus.Failed -> {
                        BuiltInModel.errorLabel(result.code.name)
                            ?: ConnectionTestMapping.codeLabel(result.code)
                    }
                }
            }
        Text(
            stringResource(status),
            Modifier.testTag("builtin-model-connection"),
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = actions.onTest, enabled = !testing) {
            Text(stringResource(if (testing) R.string.provider_testing else R.string.provider_connection_test))
        }
    }
}
