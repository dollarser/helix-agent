package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.helix.app.plugin.BundledPluginsSection
import com.helix.app.plugin.PluginService
import com.helix.app.skills.SkillAuthoringSection
import com.helix.app.skills.SkillAuthoringService
import com.helix.app.skills.SkillInstallationSection
import com.helix.app.skills.SkillInstallationService
import com.helix.app.ui.indicatedVerticalScroll
import kotlinx.coroutines.launch

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
    androidx.activity.compose.BackHandler(mobileSettings) { mobileSettings = false }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var useSession by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var catalogRevision by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val onUse: () -> Unit = {
        if (prepareSession == null) onSessionSettings() else scope.launch { useSession = prepareSession() }
    }
    useSession?.let { id ->
        com.helix.app.connector
            .ConnectorSessionPanel(connectors, id, { selectedTab = 0 }) { useSession = null }
    }

    Column(
        Modifier
            .fillMaxSize()
            .indicatedVerticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("screen-extensions"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (mobileSettings) {
            androidx.compose.material3.TextButton({ mobileSettings = false }) {
                Text(stringResource(R.string.permissions_back))
            }
            Text("Mobile Use")
            com.helix.app.automation.AutomationModule
                .Settings(onPermissions)
            return@Column
        }
        if (marketplace != null) {
            ExtensionTabBar(
                selectedTab = selectedTab,
                onSelectTab = { selectedTab = it },
            )
            HorizontalDivider()
        }

        OutlinedButton(onClick = { adding = !adding }, modifier = Modifier.testTag("extensions-add")) {
            Text(stringResource(R.string.extensions_add))
        }
        if (adding) {
            ConnectorSection(
                connectors,
                includeBundled = false,
                showInstalled = false,
                onUse = onUse,
                onInstalled = { catalogRevision++ },
            )
            if (authoring != null && installation != null) {
                SkillInstallationSection(authoring, installation, onUse, onInstalled = { catalogRevision++ })
                SkillAuthoringSection(authoring)
            }
        }
        if (marketplace != null && selectedTab == 1) {
            MarketplaceSection(
                service = marketplace,
                onConfigureRequested = { selectedTab = 0 },
            )
        } else {
            OutlinedTextField(query, {
                query = it
            }, label = { Text(stringResource(R.string.extensions_search)) }, modifier = Modifier.fillMaxWidth())
            BundledPluginsSection(connectors) { mobileSettings = true }
            androidx.compose.runtime.key(catalogRevision) {
                ConnectorSection(connectors, includeBundled = false, showImport = false, onUse = onUse, query = query)
                StandaloneSkillsSection(connectors, onUse, query)
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun ExtensionTabBar(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (selectedTab == 0) {
            Button(
                onClick = { onSelectTab(0) },
                modifier = Modifier.weight(1f).testTag("extensions-tab-manage"),
            ) {
                Text(stringResource(R.string.marketplace_tab_manage))
            }
        } else {
            OutlinedButton(
                onClick = { onSelectTab(0) },
                modifier = Modifier.weight(1f).testTag("extensions-tab-manage"),
            ) {
                Text(stringResource(R.string.marketplace_tab_manage))
            }
        }

        if (selectedTab == 1) {
            Button(
                onClick = { onSelectTab(1) },
                modifier = Modifier.weight(1f).testTag("extensions-tab-market"),
            ) {
                Text(stringResource(R.string.marketplace_tab_market))
            }
        } else {
            OutlinedButton(
                onClick = { onSelectTab(1) },
                modifier = Modifier.weight(1f).testTag("extensions-tab-market"),
            ) {
                Text(stringResource(R.string.marketplace_tab_market))
            }
        }
    }
}
