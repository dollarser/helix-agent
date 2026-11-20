package com.helix.app.eval

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.helix.app.connector.PluginSessionChoice
import com.helix.app.plugin.PluginName
import com.helix.app.plugin.PluginSessionRow
import com.helix.app.ui.LocalizedComposeActivity
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class PluginLabelsUiDeviceTest {
    private var language by mutableStateOf("en")
    private val names =
        listOf(
            PluginName("mobile-use", "mobile-use"),
            PluginName("browser", "browser"),
            PluginName("documents", "documents"),
            PluginName("chat-reply", "chat-reply"),
            PluginName("browser"),
            PluginName("my_MCP-plugin"),
        )

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
                            names.forEachIndexed { index, name ->
                                PluginSessionChoice(
                                    row =
                                        PluginSessionRow(
                                            id = "plugin-$index",
                                            selected = true,
                                            defaultSelected = false,
                                            available = true,
                                            ready = true,
                                            readyComponents = 1,
                                            pluginName = name,
                                        ),
                                    busy = false,
                                    onSelect = {},
                                    onConfigure = {},
                                )
                            }
                        }
                    }
                }
            }
        }

    @After fun cleanup() {
        LocalizedComposeActivity.content = {}
    }

    @Test fun selectedPluginNamesFollowLanguageChangesAndPreserveAuthorNames() {
        assertLabels("Mobile Use", "Browser", "Documents", "Chat Reply")
        compose.runOnIdle { language = "zh-CN" }
        assertLabels("手机操作", "浏览器", "文档", "消息回复")
        compose.runOnIdle { language = "en" }
        assertLabels("Mobile Use", "Browser", "Documents", "Chat Reply")
        compose.runOnIdle { language = "fr-FR" }
        assertLabels("Mobile Use", "Browser", "Documents", "Chat Reply")
    }

    private fun assertLabels(vararg labels: String) {
        (labels.toList() + listOf("browser", "my_MCP-plugin")).forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        val target = java.io.File(compose.activity.cacheDir, "plugin-labels-$language.png")
        target.outputStream().use { stream ->
            check(
                compose.onRoot().captureToImage().asAndroidBitmap().compress(
                    android.graphics.Bitmap.CompressFormat.PNG,
                    100,
                    stream,
                ),
            )
        }
    }
}
