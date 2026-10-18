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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import kotlinx.coroutines.launch

internal const val MOBILE_USE_SETTINGS_ROUTE = "extensions/mobile-use/settings"

@Composable
@Suppress("FunctionName")
internal fun MobileUseSettingsScreen(
    onPermissions: () -> Unit,
    onBack: () -> Unit,
    onSaved: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Mobile Use", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
        com.helix.app.automation.AutomationModule
            .Settings(onPermissions, onBack, onSaved)
    }
}

/** Navigation to managed extensions and marketplace catalog. */
@Composable
@Suppress("FunctionName", "LongMethod")
fun ExtensionsScreen(
    authoring: SkillAuthoringService?,
    installation: SkillInstallationService?,
    connectors: PluginService,
    marketplace: MarketplaceService? = null,
    onSessionSettings: () -> Unit = {},
    prepareSession: (suspend () -> String?)? = null,
    onPermissions: () -> Unit = {},
) {
    var mobileSettings by rememberSaveable { mutableStateOf(false) }
    var returnSession by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var useSession by rememberSaveable { mutableStateOf<String?>(null) }
    var usePluginId by rememberSaveable { mutableStateOf<String?>(null) }
    var configurePluginId by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var catalogRevision by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val onUse: () -> Unit = {
        usePluginId = null
        if (prepareSession == null) onSessionSettings() else scope.launch { useSession = prepareSession() }
    }
    val onUsePlugin: (String?) -> Unit = { pluginId ->
        usePluginId = pluginId
        if (prepareSession == null) onSessionSettings() else scope.launch { useSession = prepareSession() }
    }
    useSession?.let { id ->
        com.helix.app.connector
            .ConnectorSessionPanel(connectors, id, {
                if (usePluginId == "bundled-plugin:mobile-use") {
                    returnSession = id
                    mobileSettings = true
                } else {
                    selectedTab = 0
                    configurePluginId = usePluginId
                }
            }, pluginId = usePluginId, onSystemPermissions = onPermissions, onMobileUseConfiguration = {
                returnSession = id
                mobileSettings = true
            }) { useSession = null }
    }

    if (mobileSettings) {
        MobileUseSettingsScreen(onPermissions, onBack = {
            mobileSettings = false
            useSession = returnSession
            returnSession = null
        }, onSaved = {
            if (returnSession != null) {
                mobileSettings = false
                useSession = returnSession
                returnSession = null
            }
        })
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .indicatedVerticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("screen-extensions"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ExtensionTabBar(selectedTab, marketplace != null) { selectedTab = it }
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
            )
            if (authoring != null && installation != null) {
                SettingsDisclosure(
                    stringResource(R.string.plugins_import_skill),
                    "plugins-skill-import",
                    stringResource(R.string.plugins_import_skill_summary),
                ) { SkillInstallationSection(authoring, installation, onUse, onInstalled = { catalogRevision++ }) }
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
                    onPluginSettings = { mobileSettings = true },
                    onSystemPermissions = onPermissions,
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
