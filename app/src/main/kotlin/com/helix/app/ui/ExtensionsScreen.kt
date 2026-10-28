package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.connector.ConnectorSection
import com.helix.app.marketplace.MarketplaceSection
import com.helix.app.marketplace.MarketplaceService
import com.helix.app.plugin.PluginService
import com.helix.app.skills.SkillAuthoringSection
import com.helix.app.skills.SkillAuthoringService
import com.helix.app.skills.SkillInstallationSection
import com.helix.app.skills.SkillInstallationService
import com.helix.app.ui.indicatedVerticalScroll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

@Composable
@Suppress("FunctionName")
internal fun MobileUseSettingsScreen(
    onPermissions: () -> Unit,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    com.helix.app.automation.AutomationModule
        .Settings(onPermissions, onBack, onSaved)
}

/** Navigation to managed extensions and marketplace catalog. */
@Composable
// Route-owned capabilities and navigation callbacks; cancellation propagates.
@Suppress("FunctionName", "LongMethod", "LongParameterList", "TooGenericExceptionCaught")
fun ExtensionsScreen(
    authoring: SkillAuthoringService?,
    installation: SkillInstallationService?,
    connectors: PluginService,
    marketplace: MarketplaceService? = null,
    onSessionSettings: () -> Unit = {},
    prepareSession: (suspend (String) -> String?)? = null,
    currentSession: () -> String? = { null },
    onMobileUseSession: (String) -> Unit = {},
    onMobileUseSettings: () -> Unit = {},
    onPermissions: () -> Unit = {},
    importFiles: com.helix.app.files.FileManagerService? = null,
    filePicker: FileSelectionContent? = null,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var useSession by rememberSaveable { mutableStateOf<String?>(null) }
    var usePluginId by rememberSaveable { mutableStateOf<String?>(null) }
    var configurePluginId by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var catalogRevision by rememberSaveable { mutableIntStateOf(0) }
    var useFailed by remember { mutableStateOf(false) }
    val departure = LocalNavigationDeparture.current
    val onUsePlugin =
        rememberPluginUseAction(
            prepareSession,
            currentSession,
            onSessionSettings,
            onMobileUse = { target ->
                useFailed = false
                onMobileUseSession(target)
            },
            onSelected = { pluginId, target ->
                useFailed = false
                usePluginId = pluginId
                useSession = target
            },
            onFailure = { useFailed = true },
        )
    val onUse: () -> Unit = { onUsePlugin(null) }
    val currentTarget = currentSession()
    LaunchedEffect(currentTarget) {
        if (useSession != null && useSession != currentTarget) useSession = null
    }
    useSession?.let { id ->
        com.helix.app.connector.ConnectorSessionPanel(
            connectors,
            id,
            onConfigure = {
                useSession = null
                selectedTab = 0
                configurePluginId = usePluginId
            },
            pluginId = usePluginId,
            canApply = { currentSession() == id },
            onMobileUseConfiguration = { if (currentSession() == id) onMobileUseSession(id) },
        ) { useSession = null }
    }

    Column(
        Modifier
            .fillMaxSize()
            .indicatedVerticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("screen-extensions"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ExtensionTabBar(selectedTab, marketplace != null) { target ->
            val source = selectedTab
            if (departure == null) {
                selectedTab = target
            } else {
                departure.request({ selectedTab == source }) {
                    selectedTab = target
                }
            }
        }
        if (useFailed) Text(stringResource(R.string.connector_session_failed))
        Text(
            stringResource(
                extensionTabIntroduction(selectedTab),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (selectedTab == 2) {
            ConnectorSection(
                connectors,
                showInstalled = false,
                showIntroduction = false,
                onUse = onUsePlugin,
                onInstalled = { catalogRevision++ },
                onConfigureImported = { id ->
                    selectedTab = 0
                    configurePluginId = id
                },
                importFiles = importFiles,
                filePicker = filePicker,
            )
            if (authoring != null && installation != null) {
                SettingsDisclosure(
                    stringResource(R.string.plugins_import_skill),
                    "plugins-skill-import",
                    stringResource(R.string.plugins_import_skill_summary),
                ) {
                    SkillInstallationSection(
                        authoring,
                        installation,
                        onUse,
                        onInstalled = { catalogRevision++ },
                        importFiles = importFiles,
                        filePicker = filePicker,
                    )
                }
                SettingsDisclosure(
                    stringResource(R.string.plugins_authoring),
                    "plugins-authoring",
                    stringResource(R.string.plugins_authoring_summary),
                ) { SkillAuthoringSection(authoring) }
            }
        } else if (marketplace != null && selectedTab == 1) {
            MarketplaceSection(
                service = marketplace,
                onConfigureRequested = { id ->
                    selectedTab = 0
                    configurePluginId = id
                },
                onUseRequested = onUsePlugin,
            )
        } else {
            OutlinedTextField(
                query,
                {
                    query = it
                },
                label = { Text(stringResource(R.string.extensions_search)) },
                modifier = Modifier.fillMaxWidth().testTag("extensions-search"),
            )
            androidx.compose.runtime.key(catalogRevision) {
                ConnectorSection(
                    connectors,
                    showImport = false,
                    showIntroduction = false,
                    onUse = onUsePlugin,
                    query = query,
                    configurePluginId = configurePluginId,
                    onConfigurationShown = { configurePluginId = null },
                    onConfigureImported = { configurePluginId = it },
                    onPluginSettings = onMobileUseSettings,
                    onSystemPermissions = onPermissions,
                    importFiles = importFiles,
                    filePicker = filePicker,
                )
                StandaloneSkillsSection(connectors, onUse, query)
            }
        }
    }
}

private fun extensionTabIntroduction(selectedTab: Int): Int =
    when (selectedTab) {
        1 -> R.string.plugins_discover_intro
        2 -> R.string.plugins_add_intro
        else -> R.string.plugins_manage_intro
    }

@Composable
@Suppress("FunctionName")
private fun ExtensionTabBar(
    selectedTab: Int,
    showDiscover: Boolean,
    onSelectTab: (Int) -> Unit,
) {
    val tabs = if (showDiscover) listOf(0, 1, 2) else listOf(0, 2)
    val labels = listOf(R.string.marketplace_tab_manage, R.string.marketplace_tab_market, R.string.plugins_tab_add)
    val tags = listOf("extensions-tab-manage", "extensions-tab-market", "extensions-add")
    PrimaryScrollableTabRow(selectedTabIndex = tabs.indexOf(selectedTab).coerceAtLeast(0), edgePadding = 0.dp) {
        tabs.forEach { tab ->
            Tab(
                selected = selectedTab == tab,
                onClick = { onSelectTab(tab) },
                text = { Text(stringResource(labels[tab])) },
                modifier = Modifier.testTag(tags[tab]),
            )
        }
    }
}

/** Only prepares the clicked conversation. There is no saved intent to auto-enable after navigation. */
@Composable
@Suppress("TooGenericExceptionCaught") // Surface preparation failure without leaking diagnostics.
private fun rememberPluginUseAction(
    prepare: (suspend (String) -> String?)?,
    current: () -> String?,
    onFallback: () -> Unit,
    onMobileUse: (String) -> Unit,
    onSelected: (String?, String) -> Unit,
    onFailure: () -> Unit,
): (String?) -> Unit {
    var preparing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    return { pluginId ->
        val target = current()
        if (!preparing) {
            if (prepare == null || target == null) {
                onFallback()
            } else {
                preparing = true
                scope.launch {
                    try {
                        val prepared = prepare(target)
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        if (prepared == target && current() == target) {
                            if (pluginId == com.helix.app.connector.MOBILE_USE_PLUGIN_ID) {
                                onMobileUse(target)
                            } else {
                                onSelected(pluginId, target)
                            }
                        }
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (_: Exception) {
                        onFailure()
                    } finally {
                        preparing = false
                    }
                }
            }
        }
    }
}
