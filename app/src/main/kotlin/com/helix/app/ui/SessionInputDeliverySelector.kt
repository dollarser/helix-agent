package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.core.storage.repository.SessionInputDelivery

/** A deliberate delivery choice for the composer; Steer is never rebound silently. */
@Composable
@Suppress("FunctionName")
internal fun SessionInputDeliverySelector(
    delivery: SessionInputDelivery,
    expectedTurnId: String?,
    activeTurnId: String?,
    enabled: Boolean,
    onSelect: (SessionInputDelivery, String?) -> Unit,
) {
    val steerCurrent = expectedTurnId != null && expectedTurnId == activeTurnId
    val steerAvailable = activeTurnId != null
    if (!steerAvailable && delivery == SessionInputDelivery.QUEUE) return
    Column(modifier = Modifier.testTag("session-input-delivery-selector")) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                enabled = enabled,
                onClick = { onSelect(SessionInputDelivery.QUEUE, null) },
                modifier = Modifier.testTag("session-input-delivery-queue"),
            ) {
                Text(
                    stringResource(R.string.session_input_delivery_queue) +
                        if (delivery == SessionInputDelivery.QUEUE) " ✓" else "",
                )
            }
            TextButton(
                enabled = enabled && steerAvailable,
                onClick = { onSelect(SessionInputDelivery.STEER, activeTurnId) },
                modifier = Modifier.testTag("session-input-delivery-steer"),
            ) {
                Text(
                    stringResource(R.string.session_input_delivery_steer) +
                        if (delivery == SessionInputDelivery.STEER) " ✓" else "",
                )
            }
        }
        if (delivery == SessionInputDelivery.STEER && !steerCurrent) {
            Text(
                stringResource(R.string.session_input_steer_expired),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("session-input-steer-expired"),
            )
        }
    }
}
