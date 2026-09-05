package com.helix.app.root

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.core.model.Clock
import com.helix.core.model.SafetyProfile
import com.helix.core.policy.GrantState
import com.helix.core.policy.UserScope
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolRegistry
import com.helix.tools.root.LibsuRootAccess
import com.helix.tools.root.RootAccessStatus
import com.helix.tools.root.RootGrantState
import com.helix.tools.root.RootRequestStatus
import com.helix.tools.root.RootServiceState
import com.helix.tools.root.RootSessionManager
import com.helix.tools.root.RootSessionStatus
import com.helix.tools.root.RootTools
import kotlinx.coroutines.delay

/** Developer-only explicit Root capability and short-lived RootSession UI. */
internal object RootModule {
    private var access: LibsuRootAccess? = null
    private var sessions: RootSessionManager? = null

    @Synchronized
    fun register(
        context: Context,
        clock: Clock,
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
    ) {
        if (access != null) return
        val rootAccess = LibsuRootAccess(context.applicationContext)
        val manager = RootSessionManager(clock, rootAccess::status, rootAccess::disconnect)
        RootTools(rootAccess, manager).register(registry, implementations)
        access = rootAccess
        sessions = manager
    }

    fun capabilityState(): GrantState =
        when (access?.status()?.grant) {
            RootGrantState.GRANTED -> GrantState.GRANTED
            RootGrantState.DENIED, RootGrantState.LOST -> GrantState.DENIED
            RootGrantState.UNAVAILABLE, RootGrantState.REQUESTING, null -> GrantState.UNAVAILABLE
        }

    fun onAppBackgrounded() {
        access?.onAppBackgrounded()
    }

    fun scopeFor(toolName: String?): UserScope? =
        if (toolName?.startsWith("root.") == true && toolName != RootTools.STATUS) {
            sessions?.status()?.scope
        } else {
            null
        }

    @Composable
    @Suppress("FunctionName", "LongMethod", "ReturnCount")
    fun Section(profile: SafetyProfile) {
        if (profile != SafetyProfile.ADVANCED) return
        val rootAccess = access ?: return
        val sessionManager = sessions ?: return
        var accessStatus by remember { mutableStateOf(rootAccess.status()) }
        var sessionStatus by remember { mutableStateOf(sessionManager.status()) }
        var allowSystemEtc by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            while (true) {
                accessStatus = rootAccess.status()
                sessionStatus = sessionManager.status()
                delay(250)
            }
        }

        Column(modifier = Modifier.fillMaxWidth().testTag("settings-root-section")) {
            Text(stringResource(R.string.root_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.root_warning), style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(
                    R.string.root_state,
                    accessStatus.grant.name,
                    accessStatus.service.name,
                    sessionStatus.state.name,
                ),
                modifier = Modifier.testTag("root-state"),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = allowSystemEtc,
                    onCheckedChange = { allowSystemEtc = it },
                    enabled = sessionStatus.scope == null,
                    modifier = Modifier.testTag("root-scope-system-etc"),
                )
                Text(stringResource(R.string.root_scope_system_etc))
            }
            Row(modifier = Modifier.padding(top = 8.dp)) {
                Button(
                    onClick = {
                        val result = rootAccess.requestRoot()
                        if (result != RootRequestStatus.ALREADY_GRANTED) sessionStatus = sessionManager.status()
                    },
                    enabled = accessStatus.grant != RootGrantState.REQUESTING,
                    modifier = Modifier.testTag("root-request"),
                ) { Text(stringResource(R.string.root_request)) }
                Spacer(Modifier.padding(horizontal = 4.dp))
                OutlinedButton(
                    onClick = {
                        sessionStatus =
                            sessionManager.start(
                                if (allowSystemEtc) mapOf("system-etc" to "/system/etc") else emptyMap(),
                            )
                    },
                    enabled = accessStatus == RootAccessStatus(RootGrantState.GRANTED, RootServiceState.CONNECTED),
                    modifier = Modifier.testTag("root-session-start"),
                ) { Text(stringResource(R.string.root_session_start)) }
                Spacer(Modifier.padding(horizontal = 4.dp))
                OutlinedButton(
                    onClick = {
                        sessionManager.close()
                        sessionStatus = sessionManager.status()
                        accessStatus =
                            rootAccess.status()
                    },
                    enabled = sessionStatus.scope != null || accessStatus.grant != RootGrantState.UNAVAILABLE,
                    modifier = Modifier.testTag("root-stop"),
                ) { Text(stringResource(R.string.root_stop)) }
            }
        }
    }
}
