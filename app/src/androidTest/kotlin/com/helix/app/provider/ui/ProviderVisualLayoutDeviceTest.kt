package com.helix.app.provider.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.provider.ProviderModelsIntegrationDeviceTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt in with -e providerUiScreenshots true. All content and service implementations are synthetic. */
class ProviderVisualLayoutDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun apiServiceCard() = captureScene("api-service", managed = false)

    @Test fun subscriptionServiceCard() = captureScene("subscription-service", managed = true)

    @Test fun apiForm() = captureScene("api-form", managed = false)

    @Test fun subscriptionAccount() = captureScene("subscription-account", managed = true)

    @Test fun modelConfiguration() = captureScene("model-configuration", managed = false)

    private fun captureScene(
        name: String,
        managed: Boolean,
    ) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerUiScreenshots") == "true")
        ProviderModelsIntegrationDeviceTest.Fixture(managed = managed).use { fixture ->
            fixture.service.saveSelectedModels("p", listOf("a", "b"))
            val row =
                fixture.service.rows.value.single().copy(
                    displayName = if (managed) "演示订阅账号" else "演示 API 服务",
                )
            val form = mutableStateOf(syntheticForm())
            val actions = ProviderRowActions({}, {}, {}, {})
            compose.setContent {
                MaterialTheme {
                    when (name) {
                        "api-form" -> {
                            ProviderFormDialog(
                                form = form.value,
                                saving = false,
                                onField = { form.value = it },
                                onSave = {},
                                onDismiss = {},
                                discoveryState = ProviderFormDiscovery(listOf("a", "b")),
                            )
                        }

                        "subscription-account" -> {
                            ProviderAccountDialog(row, fixture.service) {}
                        }

                        "model-configuration" -> {
                            ProviderModelsDialog(row, fixture.service) {}
                        }

                        else -> {
                            Column(
                                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                            ) {
                                ProviderRow(row, testing = false, actions = actions, accountUnavailable = false)
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            val dialog = name !in setOf("api-service", "subscription-service")
            capture(name, dialog)
            if (!dialog) {
                compose.onNodeWithTag("provider-selected-model-toggle-a").performClick()
                capture("$name-expanded", false)
                compose.onNodeWithTag("provider-selected-model-toggle-a").performClick()
            }
            assertEquals(0, fixture.catalogCalls)
            assertEquals(0, fixture.generations)
            assertTrue(fixture.probed.isEmpty())
        }
    }

    private fun capture(
        name: String,
        dialog: Boolean,
    ) {
        compose.waitForIdle()
        val root = if (dialog) compose.onNode(isDialog()) else compose.onRoot()
        val bitmap = root.captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(requireNotNull(context.getExternalFilesDir(null)), "provider-ui-redesign")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
    }
}
