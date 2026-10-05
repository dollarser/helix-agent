package com.helix.app.deviceaccess

import com.helix.app.R
import com.helix.tools.root.RootAccessStatus
import com.helix.tools.root.RootGrantState
import com.helix.tools.root.RootServiceState

internal data class RootGrantPresentation(
    val label: Int,
    val canRequest: Boolean,
)

/** App authorization only. A cached grant says nothing about a plugin connection. */
internal fun rootGrantPresentation(
    status: RootAccessStatus,
    cached: Boolean?,
): RootGrantPresentation {
    val requesting = status.grant == RootGrantState.REQUESTING
    val connected = cached != false && status.service == RootServiceState.CONNECTED
    return RootGrantPresentation(
        label =
            when {
                requesting -> R.string.device_root_requesting
                cached == false || status.grant == RootGrantState.DENIED -> R.string.device_root_denied
                cached == true -> R.string.device_root_granted
                else -> R.string.device_root_unknown
            },
        canRequest = !requesting && !connected && status.service != RootServiceState.CONNECTING,
    )
}
