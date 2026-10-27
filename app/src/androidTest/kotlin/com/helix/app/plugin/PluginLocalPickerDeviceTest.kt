package com.helix.app.plugin

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.MainActivity
import com.helix.app.R
import com.helix.app.ui.container
import com.helix.app.ui.navigateTo
import com.helix.app.ui.resetDeterministicUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Production local picker -> bounded import -> persistence. No external endpoint/Skill execution. */
class PluginLocalPickerDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val container get() = compose.container()

    @Test fun localJsonAndZipImportReadbackMalformedInputAndCancel() {
        fixture { folder, name ->
            val service = container.pluginService
            val json = """{"mcpServers":{"$name":{"url":"https://example.invalid/mcp"}}}"""
            File(folder, "config.json").writeText(json)
            File(folder, "bad.json").writeText("not JSON")
            zip(
                File(folder, "plugin.zip"),
                mapOf(
                    ".claude-plugin/plugin.json" to """{"name":"$name"}""",
                    "skills/$name/SKILL.md" to skill(name),
                ),
            )
            val before = service.list().map { it.id }.toSet()
            try {
                openAdd()
                choose("connector-import-local", folder.name, "config.json")
                waitTag("connector-install")
                compose.onNodeWithTag("connector-install").performScrollTo().performClick()
                waitTag("plugin-import-done")
                assertTrue(service.list().any { it.hash == service.previewJson(json).contentHash })
                choose("connector-import-local", folder.name, "plugin.zip")
                waitTag("connector-install")
                compose.onNodeWithTag("connector-install").performScrollTo().performClick()
                waitTag("plugin-import-done")
                val installed = service.list().single { it.name == name }
                assertTrue(service.inspectSkill(installed.skills.single()).contains("Synthetic picker fixture"))
                val count = service.list().size
                choose("connector-import-local", folder.name, "bad.json")
                waitTag("plugin-import-error")
                assertEquals(count, service.list().size)
                compose.onNodeWithTag("connector-import-local").performScrollTo().performClick()
                compose.onNodeWithText(compose.activity.getString(R.string.common_cancel)).performClick()
                assertEquals(count, service.list().size)
                cancelSystemPicker()
                assertEquals(count, service.list().size)
            } finally {
                service.list().filter { it.id !in before }.forEach(service::remove)
            }
        }
    }

    @Test fun skillZipUsesSameLocalFilePickerAndInstallsDisabled() {
        fixture { folder, name ->
            zip(File(folder, "skill.zip"), mapOf("SKILL.md" to skill(name)))
            try {
                openAdd()
                compose.onNodeWithTag("plugins-skill-import").performScrollTo().performClick()
                choose("skill-import-local", folder.name, "skill.zip")
                waitTag("skill-installer-install")
                compose.onNodeWithTag("skill-installer-install").performScrollTo().performClick()
                waitTag("skill-installer-result")
                val row = container.skillRepository.list().single { it.key.name == name }
                assertFalse(row.enabled)
                assertTrue(
                    container.skillRepository
                        .inspect(row.key)
                        .body
                        .contains("Synthetic picker fixture"),
                )
            } finally {
                container.skillRepository.list().filter { it.key.name == name }.forEach {
                    container.skillRepository.removePermanentlyForPrivacy(it.key)
                }
            }
        }
    }

    private fun openAdd() {
        compose.navigateTo("extensions")
        compose.onNodeWithTag("extensions-add").performClick()
    }

    private fun choose(
        button: String,
        folder: String,
        file: String,
    ) {
        compose.onNodeWithTag(button).performScrollTo().performClick()
        val source = container.fileManager.defaultSource.displayName
        compose.waitUntil(10_000) { compose.onAllNodesWithText(source).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(source).performClick()
        listOf("work", folder, file).forEach {
            waitTag("files-entry-$it")
            compose.onNodeWithTag("files-entry-$it").performScrollTo().performClick()
        }
    }

    private fun cancelSystemPicker() {
        compose.onNodeWithTag("connector-import").performScrollTo().performClick()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        compose.waitUntil(10_000) {
            automation.rootInActiveWindow
                ?.packageName
                ?.toString()
                ?.endsWith("documentsui") == true
        }
        assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        waitTag("screen-extensions")
    }

    private fun fixture(block: (File, String) -> Unit) {
        check(compose.activity.packageName.endsWith(".asrvalidation"))
        compose.resetDeterministicUiState()
        val name = "picker-${UUID.randomUUID()}"
        val root = File(compose.activity.filesDir, "workspaces/app/work/$name").apply { check(mkdirs()) }
        try {
            block(root, name)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun waitTag(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun skill(name: String) =
        "---\nname: $name\ndescription: Synthetic picker fixture\n---\nSynthetic picker fixture."

    private fun zip(
        file: File,
        entries: Map<String, String>,
    ) {
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
    }
}
