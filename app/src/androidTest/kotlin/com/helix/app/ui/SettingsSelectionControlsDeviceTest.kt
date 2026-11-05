package com.helix.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.plugin.PluginAvailabilityControls
import com.helix.extensions.plugin.PluginPackageReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Freeze the first resolved frame: semantics alone cannot detect a thumb still animating from off. */
class SettingsSelectionControlsDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun resolvedOnHasCorrectFirstFrameAndKeepsMeasuredSlots() = verifyResolution(true)

    @Test fun savedOffHasCorrectFirstFrameAndKeepsMeasuredSlots() = verifyResolution(false)

    @Test fun storedPluginDefaultLoadsOnAndExplicitOffSurvivesRefreshAndReopen() {
        val service = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer.pluginService
        val name = "settings-toggle-${UUID.randomUUID()}"
        val record =
            service.install(
                PluginPackageReader().parse(
                    mapOf(
                        ".claude-plugin/plugin.json" to """{"name":"$name"}""".toByteArray(),
                        "skills/$name/SKILL.md" to
                            "---\nname: $name\ndescription: Synthetic setting fixture\n---\nLocal only".toByteArray(),
                    ),
                ),
                identity = name,
            )
        var revision by mutableIntStateOf(0)
        var shown by mutableStateOf(true)
        val tag = "connector-default-${record.id}"
        try {
            service.catalog.setDefault(record.id, true)
            compose.setContent {
                MaterialTheme {
                    Column {
                        if (shown) PluginAvailabilityControls(record, service, { revision++ }, revision)
                    }
                }
            }
            compose.waitUntil(10_000) {
                runCatching { compose.onNodeWithTag(tag).assertIsOn().assertIsEnabled() }.isSuccess
            }
            assertTrue(service.catalog.defaultSelected(record.id))
            compose.onNodeWithTag(tag).performClick()
            compose.waitUntil(10_000) {
                runCatching { compose.onNodeWithTag(tag).assertIsOff().assertIsEnabled() }.isSuccess
            }
            assertEquals(false, service.catalog.defaultSelected(record.id))
            compose.runOnIdle { shown = false }
            compose.waitForIdle()
            compose.runOnIdle { shown = true }
            compose.waitUntil(10_000) {
                runCatching { compose.onNodeWithTag(tag).assertIsOff().assertIsEnabled() }.isSuccess
            }
            assertEquals(false, service.catalog.defaultSelected(record.id))
        } finally {
            service.remove(record)
        }
    }

    private fun verifyResolution(stored: Boolean) {
        var value by mutableStateOf<Boolean?>(null)
        var writes = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f)) {
                MaterialTheme {
                    Controls(value, stored) {
                        writes++
                        value = it
                    }
                }
            }
        }
        val before =
            listOf("switch-slot", "checkbox-slot").associateWith { tag ->
                val slot = compose.onNodeWithTag(tag)
                assertNull(slot.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState))
                slot.performTouchInput { click() }
                slot.fetchSemanticsNode().boundsInRoot
            }
        assertEquals(
            2,
            compose
                .onAllNodes(
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState),
                ).fetchSemanticsNodes()
                .size,
        )
        assertEquals(0, writes)
        compose.runOnIdle { value = stored }
        compose.mainClock.advanceTimeByFrame()
        for (kind in listOf("switch", "checkbox")) {
            val resolved = compose.onNodeWithTag("resolved-$kind")
            if (stored) resolved.assertIsOn() else resolved.assertIsOff()
            assertEquals(
                before.getValue("$kind-slot"),
                compose.onNodeWithTag("$kind-slot").fetchSemanticsNode().boundsInRoot,
            )
            val actual = resolved.captureToImage().asAndroidBitmap()
            val reference = compose.onNodeWithTag("reference-$kind").captureToImage().asAndroidBitmap()
            assertTrue(
                "$kind must start at the stored value without a synthetic toggle animation",
                actual.sameAs(reference),
            )
        }
        assertEquals(0, writes)
        compose.onNodeWithTag("resolved-switch").performClick()
        compose.mainClock.advanceTimeBy(400)
        assertEquals(1, writes)
        assertEquals(!stored, value)
    }

    @Composable
    @Suppress("FunctionName")
    private fun Controls(
        value: Boolean?,
        stored: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        Column {
            Box(Modifier.testTag("switch-slot")) {
                SettingsSwitch(value, onChange, Modifier.testTag("resolved-switch"))
            }
            SettingsSwitch(stored, {}, Modifier.testTag("reference-switch"))
            Box(Modifier.testTag("checkbox-slot")) {
                SettingsCheckbox(value, onChange, Modifier.testTag("resolved-checkbox"))
            }
            SettingsCheckbox(stored, {}, Modifier.testTag("reference-checkbox"))
        }
    }
}
