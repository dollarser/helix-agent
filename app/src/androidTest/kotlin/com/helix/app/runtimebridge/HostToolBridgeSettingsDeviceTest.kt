package com.helix.app.runtimebridge

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.R
import com.helix.app.ui.QuickJsSettingsSection
import com.helix.runtime.quickjs.JsNativeAccessSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Dedicated preference namespaces; never changes the daily script/native settings or grants. */
class HostToolBridgeSettingsDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun defaultOnPreservesExplicitOffAndRevocationAcrossRecreation() =
        withSettings { context ->
            val settings = HostToolBridgeSettings(context)
            assertTrue(settings.enabled)
            assertEquals(0L, settings.revision)
            assertTrue(settings.allows(0))
            assertFalse(JsNativeAccessSettings(context).enabled)
            settings.setEnabled(false)
            val closedRevision = settings.revision
            assertFalse(HostToolBridgeSettings(context).enabled)
            assertFalse(settings.allows(0))
            settings.setEnabled(true)
            assertTrue(HostToolBridgeSettings(context).enabled)
            assertFalse(settings.allows(0))
            assertFalse(settings.allows(closedRevision))
            assertTrue(settings.allows(settings.revision))
            assertFalse(JsNativeAccessSettings(context).enabled)
        }

    @Test fun malformedSavedSettingDoesNotBecomeDefaultAuthorization() =
        withSettings { context ->
            assertTrue(
                context
                    .getSharedPreferences(
                        "host-tool-bridge",
                        Context.MODE_PRIVATE,
                    ).edit()
                    .putString("enabled", "invalid")
                    .commit(),
            )
            assertThrows(ClassCastException::class.java) { HostToolBridgeSettings(context).enabled }
        }

    @Test fun runtimeSettingLoadsOnWithoutEnablingNativeAccess() =
        withSettings { context ->
            compose.setContent {
                CompositionLocalProvider(LocalContext provides context) {
                    MaterialTheme { Column { QuickJsSettingsSection() } }
                }
            }
            compose.waitUntil(10_000) {
                compose
                    .onAllNodesWithTag("settings-script-tool-bridge")
                    .fetchSemanticsNodes()
                    .singleOrNull()
                    ?.config
                    ?.getOrNull(SemanticsProperties.ToggleableState) != null &&
                    compose.onAllNodesWithTag("settings-script-tool-bridge").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("settings-script-tool-bridge").assertIsOn().assertIsEnabled()
            assertTrue(HostToolBridgeSettings(context).enabled)
            assertFalse(JsNativeAccessSettings(context).enabled)
        }

    @Test fun runtimeSettingKeepsTheSavedOffValueOnOpening() =
        withSettings { context ->
            val settings = HostToolBridgeSettings(context)
            settings.setEnabled(false)
            val revision = settings.revision
            compose.setContent {
                CompositionLocalProvider(LocalContext provides context) {
                    MaterialTheme { Column { QuickJsSettingsSection() } }
                }
            }
            compose.waitUntil(10_000) {
                compose
                    .onAllNodesWithTag("settings-script-tool-bridge")
                    .fetchSemanticsNodes()
                    .singleOrNull()
                    ?.config
                    ?.getOrNull(SemanticsProperties.ToggleableState) != null
            }
            compose.onNodeWithTag("settings-script-tool-bridge").assertIsOff().assertIsEnabled()
            assertFalse(settings.enabled)
            assertEquals(revision, settings.revision)
        }

    @Test fun unreadableSettingShowsFailureInsteadOfAnOffFallback() =
        withSettings { context ->
            check(
                context
                    .getSharedPreferences(
                        "host-tool-bridge",
                        Context.MODE_PRIVATE,
                    ).edit()
                    .putString("enabled", "invalid")
                    .commit(),
            )
            compose.setContent {
                CompositionLocalProvider(LocalContext provides context) {
                    MaterialTheme { Column { QuickJsSettingsSection() } }
                }
            }
            compose.waitUntil(10_000) {
                compose
                    .onAllNodesWithText(
                        context.getString(R.string.quickjs_native_save_failed),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText(context.getString(R.string.quickjs_native_save_failed)).assertIsDisplayed()
            val rows = compose.onAllNodesWithTag("settings-script-tool-bridge").fetchSemanticsNodes()
            assertTrue(rows.none { it.config.getOrNull(SemanticsProperties.ToggleableState) != null })
            assertThrows(ClassCastException::class.java) { HostToolBridgeSettings(context).enabled }
            check(
                context
                    .getSharedPreferences(
                        "host-tool-bridge",
                        Context.MODE_PRIVATE,
                    ).edit()
                    .putBoolean("enabled", true)
                    .commit(),
            )
            compose.onNodeWithText(context.getString(R.string.chat_retry)).performClick()
            compose.waitUntil(10_000) {
                compose
                    .onAllNodesWithTag("settings-script-tool-bridge")
                    .fetchSemanticsNodes()
                    .singleOrNull()
                    ?.config
                    ?.getOrNull(SemanticsProperties.ToggleableState) != null
            }
            compose.onNodeWithTag("settings-script-tool-bridge").assertIsOn().assertIsEnabled()
        }

    private fun withSettings(block: (Context) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "bridge-default-test-${UUID.randomUUID()}-"
        val context =
            object : ContextWrapper(base) {
                override fun getApplicationContext(): Context = this

                override fun getSharedPreferences(
                    name: String,
                    mode: Int,
                ) = base.getSharedPreferences(prefix + name, mode)
            }
        val nativeBefore = JsNativeAccessSettings(base).enabled
        try {
            block(context)
            assertEquals(nativeBefore, JsNativeAccessSettings(base).enabled)
        } finally {
            base.deleteSharedPreferences(prefix + "host-tool-bridge")
            base.deleteSharedPreferences(prefix + "quickjs-native-access")
        }
    }
}
