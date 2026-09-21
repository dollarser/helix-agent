package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.connector.ConnectorSection
import com.helix.app.connector.ConnectorService
import com.helix.app.marketplace.MarketplaceSection
import com.helix.app.marketplace.MarketplaceService
import com.helix.app.skills.SkillAuthoringSection
import com.helix.app.skills.SkillAuthoringService
import com.helix.app.skills.SkillInstallationSection
import com.helix.app.skills.SkillInstallationService

/** Navigation to managed extensions and marketplace catalog. */
@Composable
@Suppress("FunctionName", "LongMethod")
fun ExtensionsScreen(
    authoring: SkillAuthoringService?,
    installation: SkillInstallationService?,
    connectors: ConnectorService,
    marketplace: MarketplaceService? = null,
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("screen-extensions"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (marketplace != null) {
            ExtensionTabBar(
                selectedTab = selectedTab,
                onSelectTab = { selectedTab = it },
            )
            HorizontalDivider()
        }

        if (marketplace != null && selectedTab == 0) {
            MarketplaceSection(
                service = marketplace,
                onConfigureRequested = { selectedTab = 1 },
            )
        } else {
            authoring?.let {
                SkillAuthoringSection(it)
                HorizontalDivider()
            }
            if (authoring != null && installation != null) {
                SkillInstallationSection(authoring, installation)
                HorizontalDivider()
            }
            ConnectorSection(connectors)
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
                modifier = Modifier.weight(1f).testTag("extensions-tab-market"),
            ) {
                Text(stringResource(R.string.marketplace_tab_market))
            }
        } else {
            OutlinedButton(
                onClick = { onSelectTab(0) },
                modifier = Modifier.weight(1f).testTag("extensions-tab-market"),
            ) {
                Text(stringResource(R.string.marketplace_tab_market))
            }
        }

        if (selectedTab == 1) {
            Button(
                onClick = { onSelectTab(1) },
                modifier = Modifier.weight(1f).testTag("extensions-tab-manage"),
            ) {
                Text(stringResource(R.string.marketplace_tab_manage))
            }
        } else {
            OutlinedButton(
                onClick = { onSelectTab(1) },
                modifier = Modifier.weight(1f).testTag("extensions-tab-manage"),
            ) {
                Text(stringResource(R.string.marketplace_tab_manage))
            }
        }
    }
}
