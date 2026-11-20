package com.helix.app.eval

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.helix.app.R
import com.helix.app.plugin.ExpertAbilityChoice
import com.helix.app.plugin.PluginName
import com.helix.app.ui.ExpertDependenciesEditor
import com.helix.app.ui.LocalizedComposeActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class ExpertPluginLabelsUiDeviceTest {
    private var language by mutableStateOf("en")
    private var selected by mutableStateOf(emptyList<String>())
    private val choices =
        listOf(
            ExpertAbilityChoice("native:browser", "browser", true, PluginName("browser", "browser")),
            ExpertAbilityChoice("package:third-party", "my_MCP-plugin", true, PluginName("my_MCP-plugin")),
            ExpertAbilityChoice("skill:browser", "browser", true),
        )

    @get:Rule val compose =
        createAndroidComposeRule<LocalizedComposeActivity>().also {
            LocalizedComposeActivity.content = {
                val context = LocalContext.current
                val config =
                    Configuration(context.resources.configuration).apply {
                        setLocale(Locale.forLanguageTag(language))
                    }
                CompositionLocalProvider(
                    LocalContext provides context.createConfigurationContext(config),
                    LocalConfiguration provides config,
                ) {
                    MaterialTheme {
                        Column {
                            ExpertDependenciesEditor(R.string.expert_linked_plugins, choices, selected, true) {
                                selected = it
                            }
                        }
                    }
                }
            }
        }

    @After fun cleanup() {
        LocalizedComposeActivity.content = {}
    }

    @Test fun languageChangesLabelsButNotSkillNamesOrSelectedReferences() {
        compose.onNodeWithText("Browser").assertIsDisplayed()
        compose.onNodeWithTag("expert-dependency-native:browser").performClick()
        compose.runOnIdle { language = "zh-CN" }
        compose.onNodeWithText("浏览器").assertIsDisplayed()
        compose.onNodeWithText("browser").assertIsDisplayed()
        compose.onNodeWithText("my_MCP-plugin").assertIsDisplayed()
        compose.onNodeWithTag("expert-dependency-native:browser").assertIsOn()
        compose.runOnIdle {
            assertEquals(listOf("native:browser"), selected)
            language = "en"
        }
        compose.onNodeWithText("Browser").assertIsDisplayed()
        compose.onNodeWithTag("expert-dependency-native:browser").assertIsOn()
    }
}
