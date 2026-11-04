package com.helix.app.eval

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.helix.app.plugin.pluginLabel
import com.helix.app.ui.LocalizedComposeActivity
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class PluginLabelsUiDeviceTest {
    private var language by mutableStateOf("zh-CN")

    @get:Rule val compose =
        createAndroidComposeRule<LocalizedComposeActivity>().also {
            LocalizedComposeActivity.content = {
                val context = LocalContext.current
                val config =
                    Configuration(
                        context.resources.configuration,
                    ).apply { setLocale(Locale.forLanguageTag(language)) }
                CompositionLocalProvider(
                    LocalContext provides context.createConfigurationContext(config),
                    LocalConfiguration provides config,
                ) {
                    MaterialTheme {
                        Column {
                            Text(pluginLabel("mobile-use"))
                            Text(pluginLabel("browser"))
                            Text(pluginLabel("my_plugin"))
                        }
                    }
                }
            }
        }

    @After fun cleanup() {
        LocalizedComposeActivity.content = {}
    }

    @Test fun displayNamesFollowTheUiLanguageWithoutChangingIdentifiers() {
        compose.onNodeWithText("手机操作").assertIsDisplayed()
        compose.onNodeWithText("浏览器").assertIsDisplayed()
        compose.onNodeWithText("My Plugin").assertIsDisplayed()
        compose.runOnIdle { language = "en" }
        compose.onNodeWithText("Mobile Use").assertIsDisplayed()
        compose.onNodeWithText("Browser").assertIsDisplayed()
        compose.onNodeWithText("My Plugin").assertIsDisplayed()
    }
}
