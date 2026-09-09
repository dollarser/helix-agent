package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.helix.app.connector.ConnectorSection
import com.helix.app.connector.ConnectorService
import com.helix.app.skills.SkillAuthoringSection
import com.helix.app.skills.SkillAuthoringService
import com.helix.app.skills.SkillInstallationSection
import com.helix.app.skills.SkillInstallationService

/** Navigation to existing managed extensions; entering never enables an endpoint. */
@Composable
@Suppress("FunctionName")
fun ExtensionsScreen(
    authoring: SkillAuthoringService?,
    installation: SkillInstallationService?,
    connectors: ConnectorService,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("screen-extensions"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        authoring?.let { SkillAuthoringSection(it) }
        HorizontalDivider()
        if (authoring != null && installation != null) {
            SkillInstallationSection(authoring, installation)
        }
        HorizontalDivider()
        ConnectorSection(connectors)
    }
}
