package com.helix.feature.conversation.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.helix.feature.conversation.R
import com.helix.feature.conversation.presentation.StopEffect
import com.helix.feature.conversation.presentation.TurnStopFacts

@Composable
@Suppress("FunctionName")
fun StopFactsSummary(facts: TurnStopFacts) {
    Column {
        Text(stringResource(R.string.stop_facts_title), style = MaterialTheme.typography.labelMedium)
        StopEffect.entries.forEach { effect ->
            val calls = facts.actions.filter { it.effect == effect }
            if (calls.isNotEmpty()) {
                val label =
                    when (effect) {
                        StopEffect.VERIFIED -> R.string.stop_facts_verified
                        StopEffect.RECORDED -> R.string.stop_facts_recorded
                        StopEffect.UNKNOWN -> R.string.stop_facts_unknown
                        StopEffect.NOT_STARTED -> R.string.stop_facts_not_started
                    }
                Text(stringResource(label, calls.joinToString { it.name }), style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(
            stringResource(R.string.stop_facts_artifacts, facts.artifactCount),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(stringResource(R.string.stop_facts_boundary), style = MaterialTheme.typography.bodySmall)
    }
}
