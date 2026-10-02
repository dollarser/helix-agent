package com.helix.app.automation

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.app.vision.MobileUseScreenTarget
import com.helix.core.model.SafetyProfile
import com.helix.core.policy.MobileUseGrant
import com.helix.core.policy.MobileUseGrantStore
import com.helix.core.policy.UserScope
import com.helix.extensions.mobileuse.MobileUsePlugin
import com.helix.extensions.plugin.PluginRegistry
import com.helix.tools.automation.AutomationApplication
import com.helix.tools.automation.AutomationApplicationCatalog
import com.helix.tools.automation.AutomationPermissionCenter
import com.helix.tools.automation.AutomationServiceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One reusable Conversation surface; the Android system capability itself remains device-wide. */
internal object AutomationModule {
    private var appContext: Context? = null
    private var runtime: MobileUsePlugin? = null
    private var sharingTarget: (suspend (String) -> MobileUseScreenTarget?)? = null

    @Synchronized
    fun register(
        context: Context,
        plugins: PluginRegistry,
        images: com.helix.tools.framework.ToolImagePublication,
        grants: MobileUseGrantStore,
        conversationExists: (String) -> Boolean,
        screenTarget: suspend (String) -> MobileUseScreenTarget?,
    ) {
        val plugin = runtime ?: MobileUsePlugin(context.applicationContext, images).also { runtime = it }
        if (plugins.find(MobileUsePlugin.PLUGIN_ID) == null) plugins.register(plugin)
        plugin.permissionCenter.configureConversations(grants, conversationExists)
        appContext = context.applicationContext
        sharingTarget = screenTarget
    }

    fun scopeFor(
        toolName: String?,
        conversationId: String?,
    ): UserScope? = runtime?.scopeFor(toolName, conversationId)

    @Composable
    @Suppress("FunctionName", "ReturnCount")
    fun Section(
        profile: SafetyProfile,
        conversationId: String? = null,
        prepareConversation: suspend (String) -> Boolean = { true },
    ) {
        if (profile != SafetyProfile.ADVANCED) return
        val center = runtime?.permissionCenter ?: return
        val context = appContext ?: return
        val target = sharingTarget ?: return
        if (conversationId == null) {
            Text(stringResource(R.string.automation_conversation_required))
            return
        }
        key(conversationId) { ConversationSection(conversationId, center, context, target, prepareConversation) }
    }
}

@Composable
// Each independent coroutine propagates cancellation rather than converting it into a save failure.
@Suppress(
    "FunctionName",
    "LongMethod",
    "LongParameterList",
    "CyclomaticComplexMethod",
    "SwallowedException",
    "ThrowsCount",
)
private fun ConversationSection(
    conversationId: String,
    center: AutomationPermissionCenter,
    context: Context,
    target: suspend (String) -> MobileUseScreenTarget?,
    prepare: suspend (String) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    val applications = remember(context) { AutomationApplicationCatalog(context) }
    var grant by remember { mutableStateOf<MobileUseGrant?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var wholePhone by remember { mutableStateOf(false) }
    var catalog by remember { mutableStateOf(emptyList<AutomationApplication>()) }
    var service by remember { mutableStateOf(AutomationServiceState.DISABLED) }
    var recipient by remember { mutableStateOf<MobileUseScreenTarget?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(conversationId) {
        while (true) {
            try {
                val saved = withContext(Dispatchers.IO) { center.conversationGrant(conversationId) }
                grant = saved
                if (!loaded) {
                    selected = saved?.scope?.allowedPackages.orEmpty()
                    wholePhone = saved?.scope?.allApplications == true
                    loaded = true
                }
                service = withContext(Dispatchers.IO) { center.serviceState() }
                recipient = target(conversationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                notice = R.string.automation_start_failed
            }
            delay(750)
        }
    }
    LaunchedEffect(applications) {
        try {
            catalog = withContext(Dispatchers.IO) { applications.load() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RuntimeException) {
            // The picker has its own explicit retry surface; preserve selected identities here.
        }
    }
    if (picker) {
        AutomationApplicationPicker(
            initialSelection = selected,
            loadApplications = applications::load,
            onConfirm = {
                selected = it
                picker = false
            },
            onDismiss = { picker = false },
        )
    }
    HorizontalDivider()
    Column(Modifier.fillMaxWidth().testTag("settings-automation-section")) {
        Text(stringResource(R.string.automation_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.automation_conversation_scope), Modifier.testTag("automation-conversation-scope"))
        Text(stringResource(R.string.automation_warning), style = MaterialTheme.typography.bodySmall)
        Text(
            stringResource(
                R.string.automation_state,
                service.name,
                stringResource(
                    if (grant !=
                        null
                    ) {
                        R.string.automation_grant_enabled
                    } else {
                        R.string.automation_grant_disabled
                    },
                ),
            ),
            Modifier.testTag("automation-grant-status"),
        )
        Row {
            Checkbox(
                wholePhone,
                { wholePhone = it },
                enabled = loaded && !busy,
                modifier = Modifier.testTag("automation-all-applications"),
            )
            Text(stringResource(R.string.automation_whole_phone_label), Modifier.weight(1f))
        }
        OutlinedButton(
            onClick = { picker = true },
            enabled = loaded && !busy && !wholePhone,
            modifier = Modifier.fillMaxWidth().testTag("automation-select-apps"),
        ) { Text(stringResource(R.string.automation_app_selection_count, selected.size)) }
        val labels = selected.sorted().map { name -> catalog.firstOrNull { it.packageName == name }?.label ?: name }
        Text(
            if (wholePhone) {
                stringResource(R.string.automation_app_whole_phone)
            } else {
                labels.take(3).joinToString(", ") + if (labels.size > 3) " …" else ""
            },
            Modifier.testTag("automation-selected-apps"),
        )
        Text(stringResource(R.string.automation_image_handling), style = MaterialTheme.typography.bodySmall)
        val model = recipient
        Text(
            when {
                model == null -> stringResource(R.string.automation_screen_recipient_missing)
                model.local -> stringResource(R.string.automation_screen_recipient_local, model.label)
                else -> stringResource(R.string.automation_screen_recipient, model.label, model.origin)
            },
            Modifier.testTag("automation-screen-recipient"),
            style = MaterialTheme.typography.bodySmall,
        )
        notice?.let { Text(stringResource(it), Modifier.testTag("automation-start-notice")) }
        com.helix.app.ui.SettingsActions {
            OutlinedButton(
                onClick = {
                    try {
                        context.startActivity(center.accessibilitySettingsIntent())
                    } catch (
                        _: RuntimeException,
                    ) {
                        notice = R.string.automation_service_unavailable
                    }
                },
                modifier = Modifier.testTag("automation-open-settings"),
            ) { Text(stringResource(R.string.automation_open_settings)) }
            Button(
                onClick = {
                    val chosen = selected.toSet()
                    val all = wholePhone
                    busy = true
                    scope.launch {
                        try {
                            check(prepare(conversationId)) { "Conversation changed" }
                            withContext(Dispatchers.IO) { center.authorizeConversation(conversationId, chosen, all) }
                            grant = withContext(Dispatchers.IO) { center.conversationGrant(conversationId) }
                            notice = R.string.automation_grant_saved
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: RuntimeException) {
                            notice = R.string.automation_start_failed
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = loaded && !busy && (wholePhone || selected.isNotEmpty()),
                modifier = Modifier.testTag("automation-start"),
            ) { Text(stringResource(R.string.automation_start)) }
            OutlinedButton(
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { center.revokeConversation(conversationId) }
                            grant = null
                            notice = null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: RuntimeException) {
                            notice = R.string.automation_start_failed
                        } finally {
                            busy = false
                        }
                    }
                },
                enabled = grant != null && !busy,
                modifier = Modifier.testTag("automation-stop"),
            ) { Text(stringResource(R.string.automation_stop)) }
        }
    }
}
