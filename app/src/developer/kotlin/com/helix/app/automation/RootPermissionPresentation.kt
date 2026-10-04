package com.helix.app.automation

import com.helix.app.R
import com.helix.tools.root.RootAccessStatus
import com.helix.tools.root.RootGrantState
import com.helix.tools.root.RootServiceState

/** Presentation only; a cached app grant never makes an execution backend ready. */
internal data class RootPermissionPresentation(
    val authorization: Int,
    val connection: Int,
    val action: Int,
    val canConnect: Boolean,
)

internal fun rootPermissionPresentation(
    status: RootAccessStatus?,
    cachedGrant: Boolean?,
): RootPermissionPresentation {
    val requesting = status?.grant == RootGrantState.REQUESTING
    val connected =
        cachedGrant != false && status?.grant == RootGrantState.GRANTED && status.service == RootServiceState.CONNECTED
    val connecting = status?.service == RootServiceState.CONNECTING
    return RootPermissionPresentation(
        authorization = rootAuthorizationLabel(status?.grant, cachedGrant),
        connection =
            when {
                connected -> R.string.automation_root_ready
                connecting -> R.string.mobile_root_connecting
                status?.grant == RootGrantState.LOST -> R.string.automation_root_lost
                else -> R.string.automation_root_unavailable
            },
        action = if (cachedGrant == true) R.string.mobile_root_connect else R.string.automation_root_request,
        canConnect = status != null && !requesting && !connected && !connecting,
    )
}

private fun rootAuthorizationLabel(
    grant: RootGrantState?,
    cachedGrant: Boolean?,
): Int =
    when {
        grant == RootGrantState.REQUESTING -> R.string.mobile_root_grant_requesting
        cachedGrant == false || grant == RootGrantState.DENIED -> R.string.mobile_root_grant_denied
        cachedGrant == true -> R.string.mobile_root_grant_cached
        else -> R.string.mobile_root_grant_unknown
    }
