package com.helix.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
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
internal fun AccountLoadingStatus(
    @StringRes label: Int,
    tag: String,
) {
    Column(Modifier.fillMaxWidth().testTag(tag), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(label), style = MaterialTheme.typography.bodySmall)
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

/** Structural placeholders never invent quota values while the account response is pending. */
@Composable
@Suppress("FunctionName")
internal fun AccountUsagePlaceholders() {
    Column(Modifier.testTag("account-usage-placeholders"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf(R.string.user_usage_conversation, R.string.user_usage_transcription, R.string.user_usage_speech)
            .forEach { title ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                        AccountPlaceholderLine(0.65f)
                        AccountPlaceholderLine(0.4f)
                    }
                }
            }
    }
}

@Composable
@Suppress("FunctionName")
internal fun AccountNoticePlaceholders() {
    Column(Modifier.fillMaxWidth().testTag("account-notices-placeholders")) {
        repeat(3) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AccountPlaceholderLine(0.75f)
                AccountPlaceholderLine(0.35f)
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun AccountPlaceholderLine(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth(fraction)
            .height(16.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small),
    )
}
