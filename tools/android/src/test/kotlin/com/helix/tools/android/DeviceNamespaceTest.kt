package com.helix.tools.android

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolName
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DeviceNamespaceTest {
    @Test fun registrationExposesOnlyNewSystemNames() {
        val registry = ToolRegistry()
        AndroidSystemTools.registerAll(registry, Bridge())
        assertEquals(
            setOf(
                "device.open_url",
                "device.open_settings",
                "device.clipboard_read",
                "device.clipboard_write",
                "device.share_text",
                "device.create_note",
            ),
            registry.all().map { it.name.value }.toSet(),
        )
        listOf(
            "android.open_uri",
            "android.open_settings",
            "android.share",
            "clipboard.read",
            "clipboard.write",
        ).forEach {
            assertFalse(registry.contains(ToolName(it), ToolVersion(1)))
        }
    }

    @Test fun noteUsesTitleAndReportsOnlyChooserLaunch() {
        val bridge = Bridge()
        val registry = ToolRegistry()
        DeviceCreateNoteTool.register(registry, bridge)
        val result =
            registry.executor(ToolName(DeviceCreateNoteTool.NAME), ToolVersion(1)).execute(
                ExecutableToolCall(
                    "call",
                    DeviceCreateNoteTool.NAME,
                    "1",
                    buildJsonObject {
                        put("text", JsonPrimitive("Synthetic note"))
                        put("title", JsonPrimitive("Synthetic title"))
                    },
                    ExecutionTargetType.LOCAL_ANDROID,
                    Instant.now().plusSeconds(30),
                    object : CancelSignal {
                        override fun isCancelled() = false
                    },
                ),
            ) as ToolExecutorResult.Completed
        assertEquals("Synthetic title", bridge.title)
        assertEquals(
            "chooser-opened",
            result.output.jsonObject
                .getValue("status")
                .jsonPrimitive.content,
        )
        assertTrue(DeviceCreateNoteTool.descriptor().description.contains("does not mean the note was saved"))
    }

    private class Bridge : AndroidSystemBridge {
        var title = ""

        override fun share(
            text: String,
            subject: String,
        ): ShareOutcome {
            title = subject
            return ShareOutcome(ShareStatus.CHOOSER_OPENED, "")
        }

        override fun openUri(url: String): OpenUriOutcome = error("unused")

        override fun openSettings(
            target: AndroidSettingsTarget,
            packageName: String,
        ): OpenSettingsOutcome = error("unused")

        override fun clipboardRead(): ClipboardReadOutcome = error("unused")

        override fun clipboardWrite(text: String): ClipboardWriteOutcome = error("unused")
    }
}
