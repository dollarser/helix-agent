package com.helix.app.ui

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.AppContainer
import com.helix.app.R
import com.helix.app.connector.ConnectorService
import com.helix.app.proot.ProotToolModule
import com.helix.core.model.Capability
import com.helix.core.policy.CapabilityCenter
import com.helix.core.policy.GrantState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Capability Center (P0-B, research doc section 11 / PX-04): one first-class page that
 * lists every runtime capability Helix can lean on, each showing a live status (Ready /
 * Needs permission / Off / Unavailable / N connected) and, on tap, what it enables, why it
 * is needed, its current scope, and honest Test / Repair / Disable actions. This replaces
 * scattering permission toggles across Settings.
 *
 * Statuses are computed through [CapabilityCenter.check] — a LIVE resolve plus an audit
 * record, deliberately never cached — inside a [LaunchedEffect] keyed on [revision] so the
 * audit trail only grows on entry and explicit refresh, not on every recomposition.
 */
@Composable
@Suppress("FunctionName")
internal fun CapabilitiesScreenDestination(
    container: AppContainer,
    onOpenSettings: () -> Unit,
    onOpenExtensions: () -> Unit,
) {
    val context = LocalContext.current
    var revision by remember { mutableStateOf(0) }
    var rows by remember { mutableStateOf<List<CapRow>?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(revision) {
        // buildCapabilityRows hits the live resolver (and the audit recorder → Room), so it
        // runs off the main thread, matching how ProotRuntimeSection refreshes.
        rows =
            withContext(Dispatchers.IO) {
                buildCapabilityRows(container.capabilityCenter, container.connectorService)
            }
    }

    val requestPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) revision += 1
        }
    val callbacks =
        CapCallbacks(
            onTestNotification = { postTestNotification(context) },
            onRequestPermission = requestPermission::launch,
            onOpenAppDetails = {
                openSystemSettings(
                    context,
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}"),
                )
            },
            onOpenAccessibility = { openSystemSettings(context, Settings.ACTION_ACCESSIBILITY_SETTINGS, null) },
            onOpenSettings = onOpenSettings,
            onOpenExtensions = onOpenExtensions,
        )

    Column(Modifier.fillMaxSize().testTag("screen-capabilities")) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("capabilities-header"),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { revision += 1 }, modifier = Modifier.testTag("capabilities-refresh")) {
                Text(stringResource(R.string.cap_refresh))
            }
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(rows.orEmpty(), key = CapRow::key) { row ->
                CapabilityRowView(
                    row,
                    expanded = expanded == row.key,
                    onToggle = { expanded = if (expanded == row.key) null else row.key },
                    callbacks,
                )
            }
        }
    }
}

/**
 * One capability row: a tappable card with a name and a live status chip; when expanded it
 * shows what it enables / why / current scope plus the row-specific actions.
 */
@Composable
@Suppress("FunctionName")
private fun CapabilityRowView(
    row: CapRow,
    expanded: Boolean,
    onToggle: () -> Unit,
    callbacks: CapCallbacks,
) {
    val statusText =
        when {
            row.mcpCount != null -> stringResource(R.string.cap_status_connected, row.mcpCount)
            row.customStatus != null -> row.customStatus
            else -> stringResource(row.statusRes)
        }
    val statusColor =
        when (row.grant) {
            GrantState.GRANTED -> MaterialTheme.colorScheme.primary
            GrantState.DENIED -> MaterialTheme.colorScheme.tertiary
            GrantState.UNAVAILABLE, GrantState.LOST -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .testTag("capability-${row.key}"),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(row.nameRes),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("capability-${row.key}-name"),
                )
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor,
                    modifier = Modifier.testTag("capability-${row.key}-status"),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                CapabilityDetailRow(R.string.cap_what_label, row.whatRes, "capability-${row.key}-what")
                CapabilityDetailRow(R.string.cap_why_label, row.whyRes, "capability-${row.key}-why")
                CapabilityDetailRow(R.string.cap_scope_label, row.scopeRes, "capability-${row.key}-detail")
                CapabilityActions(row, callbacks)
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun CapabilityDetailRow(
    @StringRes labelRes: Int,
    @StringRes bodyRes: Int,
    tag: String,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp).testTag(tag)) {
        Text(stringResource(labelRes), style = MaterialTheme.typography.labelMedium)
        Text(stringResource(bodyRes), style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Row-specific Test / Repair / Disable actions. Apps cannot revoke system grants
 * programmatically, so "Repair" requests the permission (or opens the right system screen)
 * and "Disable" points at the system settings where the user actually toggles it off — the
 * honest affordance, never a fake in-app switch.
 */
@Composable
@Suppress("FunctionName")
private fun CapabilityActions(
    row: CapRow,
    callbacks: CapCallbacks,
) {
    // Grant when the user has not allowed it; otherwise point at the system screen that
    // actually revokes it (the honest "Disable").
    val grantOrDisable: (String) -> CapAction = { permission ->
        if (row.grant == GrantState.DENIED) {
            CapAction("grant", R.string.cap_action_grant, { callbacks.onRequestPermission(permission) })
        } else {
            CapAction("disable", R.string.cap_action_disable, callbacks.onOpenAppDetails)
        }
    }
    val actions =
        when (row.key) {
            "notifications" -> {
                listOf(
                    CapAction("test", R.string.cap_action_test, callbacks.onTestNotification),
                    grantOrDisable(Manifest.permission.POST_NOTIFICATIONS),
                )
            }

            "calendar" -> {
                listOf(grantOrDisable(Manifest.permission.WRITE_CALENDAR))
            }

            "accessibility" -> {
                listOf(CapAction("repair", R.string.cap_action_open, callbacks.onOpenAccessibility))
            }

            "runtime" -> {
                if (ProotToolModule.AVAILABLE) {
                    listOf(CapAction("open", R.string.cap_action_open, callbacks.onOpenSettings))
                } else {
                    emptyList()
                }
            }

            "mcp" -> {
                listOf(CapAction("manage", R.string.cap_action_manage, callbacks.onOpenExtensions))
            }

            else -> {
                emptyList()
            }
        }
    if (actions.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        actions.forEach { action ->
            TextButton(
                onClick = action.onClick,
                modifier = Modifier.testTag("capability-${row.key}-${action.tag}"),
            ) {
                Text(stringResource(action.res))
            }
        }
    }
}

/**
 * Resolves the 8 capability rows: six through a LIVE [CapabilityCenter.check] per grant
 * (files / browser / notifications / calendar / accessibility / root), the developer proot
 * runtime, and the MCP connector count. [LOST] and [UNAVAILABLE] both fail closed to
 * "Unavailable"; a [DENIED] accessibility grant shows "Off" (the declared service exists but
 * the user has not switched it on) rather than "Needs permission".
 */
private fun buildCapabilityRows(
    center: CapabilityCenter,
    connector: ConnectorService,
): List<CapRow> {
    val files = center.check(Capability.SAF_DOCUMENT_TREE).state
    val browser = center.check(Capability.WEB_BROWSING).state
    val notifications = center.check(Capability.NOTIFICATION_READ).state
    val calendar = center.check(Capability.CALENDAR_WRITE).state
    val accessibility = center.check(Capability.ACCESSIBILITY_AUTOMATION).state
    val root = center.check(Capability.ROOT_SHELL).state
    val mcpCount = connector.list().sumOf { c -> c.endpoints.count { connector.enabled(it) } }
    val prootAvailable = ProotToolModule.AVAILABLE
    val prootLabel = if (prootAvailable) ProotToolModule.verifyStatusLabel() else null
    val prootGrant = if (prootAvailable) GrantState.GRANTED else GrantState.UNAVAILABLE
    val mcpGrant = if (mcpCount > 0) GrantState.GRANTED else GrantState.UNAVAILABLE
    return listOf(
        standardRow("files", files),
        standardRow("browser", browser),
        standardRow("notifications", notifications),
        standardRow("calendar", calendar),
        standardRow("accessibility", accessibility, accessibility = true),
        customRow("runtime", prootGrant, prootLabel, null, R.string.cap_status_ready),
        standardRow("root", root),
        customRow("mcp", mcpGrant, null, mcpCount, R.string.cap_status_connected),
    )
}

/** A capability row backed by a live [CapabilityCenter.check] grant. */
private fun standardRow(
    key: String,
    grant: GrantState,
    accessibility: Boolean = false,
): CapRow {
    val copy = copyFor(key)
    val statusRes =
        when {
            grant == GrantState.GRANTED -> R.string.cap_status_ready

            grant == GrantState.DENIED && accessibility -> R.string.cap_status_off

            grant == GrantState.DENIED -> R.string.cap_status_needs_permission

            // UNAVAILABLE and LOST both fail closed.
            else -> R.string.cap_status_unavailable
        }
    return CapRow(
        key = key,
        nameRes = copy.name,
        statusRes = statusRes,
        customStatus = null,
        mcpCount = null,
        grant = grant,
        whatRes = copy.what,
        whyRes = copy.why,
        scopeRes = copy.scope,
    )
}

/** A row whose status is not a plain grant: the developer proot runtime and the MCP count. */
private fun customRow(
    key: String,
    grant: GrantState,
    customStatus: String?,
    mcpCount: Int?,
    statusRes: Int,
): CapRow {
    val copy = copyFor(key)
    return CapRow(
        key = key,
        nameRes = copy.name,
        statusRes = statusRes,
        customStatus = customStatus,
        mcpCount = mcpCount,
        grant = grant,
        whatRes = copy.what,
        whyRes = copy.why,
        scopeRes = copy.scope,
    )
}

/** The model-facing copy (name + what / why / scope) per capability row key. */
private fun copyFor(key: String): CapCopy =
    when (key) {
        "files" -> filesCopy
        "browser" -> browserCopy
        "notifications" -> notificationsCopy
        "calendar" -> calendarCopy
        "accessibility" -> accessibilityCopy
        "runtime" -> runtimeCopy
        "root" -> rootCopy
        "mcp" -> mcpCopy
        else -> error("unknown capability row $key")
    }

private val filesCopy =
    CapCopy(
        R.string.cap_name_files,
        R.string.cap_files_what,
        R.string.cap_files_why,
        R.string.cap_files_scope,
    )

private val browserCopy =
    CapCopy(
        R.string.cap_name_browser,
        R.string.cap_browser_what,
        R.string.cap_browser_why,
        R.string.cap_browser_scope,
    )

private val notificationsCopy =
    CapCopy(
        R.string.cap_name_notifications,
        R.string.cap_notifications_what,
        R.string.cap_notifications_why,
        R.string.cap_notifications_scope,
    )

private val calendarCopy =
    CapCopy(
        R.string.cap_name_calendar,
        R.string.cap_calendar_what,
        R.string.cap_calendar_why,
        R.string.cap_calendar_scope,
    )

private val accessibilityCopy =
    CapCopy(
        R.string.cap_name_accessibility,
        R.string.cap_accessibility_what,
        R.string.cap_accessibility_why,
        R.string.cap_accessibility_scope,
    )

private val runtimeCopy =
    CapCopy(
        R.string.cap_name_runtime,
        R.string.cap_runtime_what,
        R.string.cap_runtime_why,
        R.string.cap_runtime_scope,
    )

private val rootCopy =
    CapCopy(
        R.string.cap_name_root,
        R.string.cap_root_what,
        R.string.cap_root_why,
        R.string.cap_root_scope,
    )

private val mcpCopy =
    CapCopy(
        R.string.cap_name_mcp,
        R.string.cap_mcp_what,
        R.string.cap_mcp_why,
        R.string.cap_mcp_scope,
    )

private data class CapRow(
    val key: String,
    @StringRes val nameRes: Int,
    @StringRes val statusRes: Int,
    val customStatus: String?,
    val mcpCount: Int?,
    val grant: GrantState,
    @StringRes val whatRes: Int,
    @StringRes val whyRes: Int,
    @StringRes val scopeRes: Int,
)

private data class CapCopy(
    @StringRes val name: Int,
    @StringRes val what: Int,
    @StringRes val why: Int,
    @StringRes val scope: Int,
)

private data class CapAction(
    val tag: String,
    @StringRes val res: Int,
    val onClick: () -> Unit,
)

private class CapCallbacks(
    val onTestNotification: () -> Unit,
    val onRequestPermission: (String) -> Unit,
    val onOpenAppDetails: () -> Unit,
    val onOpenAccessibility: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenExtensions: () -> Unit,
)

private const val TEST_CHANNEL_ID = "capability_test"
private const val TEST_NOTIFICATION_ID = 90210

private fun postTestNotification(context: Context) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(
        NotificationChannel(
            TEST_CHANNEL_ID,
            context.getString(R.string.cap_test_notification_title),
            NotificationManager.IMPORTANCE_DEFAULT,
        ),
    )
    val notification =
        Notification
            .Builder(context, TEST_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.cap_test_notification_title))
            .setContentText(context.getString(R.string.cap_test_notification_text))
            .setAutoCancel(true)
            .build()
    manager.notify(TEST_NOTIFICATION_ID, notification)
}

private fun openSystemSettings(
    context: Context,
    action: String,
    uri: Uri?,
) {
    context.startActivity(if (uri != null) Intent(action, uri) else Intent(action))
}
