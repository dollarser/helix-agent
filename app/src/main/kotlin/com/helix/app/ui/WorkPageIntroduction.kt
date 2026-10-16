package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R

/** Explains the page using the existing task and artifact model, without starting work. */
@Composable
@Suppress("FunctionName")
internal fun WorkPageIntroduction(
    title: Int,
    description: Int,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
@Suppress("FunctionName")
internal fun ArtifactsIntroduction(empty: Boolean) {
    if (empty) {
        WorkPageIntroduction(R.string.work_artifacts_empty_title, R.string.work_artifacts_empty_body)
    } else {
        Text(stringResource(R.string.work_artifacts_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
