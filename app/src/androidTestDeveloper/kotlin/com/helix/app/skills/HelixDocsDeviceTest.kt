package com.helix.app.skills

import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.chat.ModelToolExposureOrder
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.NoCancellation
import com.helix.core.model.ToolName
import com.helix.extensions.skills.SkillTools
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolSchemaValidation
import com.helix.tools.framework.ToolSchemaValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class HelixDocsDeviceTest {
    @Test
    @Suppress("LongMethod") // One guide journey keeps resource and permission assertions together.
    fun packagedGuideIsDiscoverableReadableVersionedAndFailsClosedOnUnknownPage() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val container = app.appContainer
        val repository = container.skillRepository
        val tools = container.toolPipeline.registry

        assertTrue("skills.read" in ModelToolExposureOrder.defaultNames(emptySet()))
        assertTrue("skills.read_resource" in ModelToolExposureOrder.defaultNames(emptySet()))
        assertNotNull(tools.resolveLatest(ToolName(SkillTools.READ_RESOURCE)))

        val listing = invoke(SkillTools.LIST, buildJsonObject {})
        val entry =
            listing
                .getValue("entries")
                .jsonArray
                .single { item ->
                    item.jsonObject
                        .getValue("name")
                        .jsonPrimitive.content == "helix-docs"
                }.jsonObject
        assertEquals("BUILT_IN", entry.getValue("source").jsonPrimitive.content)
        assertEquals("true", entry.getValue("enabled").jsonPrimitive.content)
        val source = entry.getValue("source").jsonPrimitive.content
        val name = entry.getValue("name").jsonPrimitive.content
        val snapshotHash = entry.getValue("snapshotHash").jsonPrimitive.content

        val instructions =
            invoke(
                SkillTools.READ,
                buildJsonObject {
                    put("source", source)
                    put("name", name)
                    put("snapshotHash", snapshotHash)
                },
            ).getValue("instructions").jsonPrimitive.content
        val manifest = readResource(source, name, snapshotHash, "references/manifest.json")
        val manifestJson = Json.parseToJsonElement(manifest).jsonObject
        val revision = manifestJson.getValue("revision").jsonPrimitive.content
        assertEquals(64, revision.length)
        assertTrue(instructions.contains(revision))
        val installed = app.packageManager.getPackageInfo(app.packageName, 0)
        assertEquals(installed.versionName, manifestJson.getValue("appVersion").jsonPrimitive.content)
        assertEquals(
            installed.longVersionCode.toString(),
            manifestJson.getValue("appVersionCode").jsonPrimitive.content,
        )
        assertEquals(app.packageName, manifestJson.getValue("applicationId").jsonPrimitive.content)
        assertEquals("developer", manifestJson.getValue("distribution").jsonPrimitive.content)

        val sections = manifestJson.getValue("sections").jsonArray.map { it.jsonObject }
        val channels = sections.single { it.getValue("id").jsonPrimitive.content == "channels" }
        val sectionPath = channels.getValue("path").jsonPrimitive.content
        val index = readResource(source, name, snapshotHash, "references/index.md")
        assertTrue(index.contains("| channels |"))
        val chapter = readResource(source, name, snapshotHash, "references/$sectionPath")
        assertTrue(chapter.startsWith("# Channels and preview status"))
        assertTrue(chapter.contains("development preview"))
        val chinese = readResource(source, name, snapshotHash, "references/zh-CN/mobile-use.md")
        assertTrue(chinese.contains("授权仅属于当前 Helix 对话"))
        assertTrue(chinese.contains("只有手动撤销结束授权"))

        val unknown =
            invokeRaw(
                SkillTools.READ_RESOURCE,
                buildJsonObject {
                    put("source", source)
                    put("name", name)
                    put("snapshotHash", snapshotHash)
                    put("path", "references/sections/not-a-real-section.md")
                },
            )
        assertTrue(unknown is ToolExecutorResult.Failed)
        assertEquals("Unknown skill resource", (unknown as ToolExecutorResult.Failed).detail)
        assertTrue(repository.listForModel(null, null).any { it.key.name == "helix-docs" })
        println("HELIX_DOCS_DEVICE=PASS revision=$revision section=channels unknown=bounded-rejection")
    }

    private fun readResource(
        source: String,
        name: String,
        snapshotHash: String,
        path: String,
    ): String {
        val output =
            invoke(
                SkillTools.READ_RESOURCE,
                buildJsonObject {
                    put("source", source)
                    put("name", name)
                    put("snapshotHash", snapshotHash)
                    put("path", path)
                },
            )
        assertEquals("utf-8", output.getValue("encoding").jsonPrimitive.content)
        val content = output.getValue("content").jsonPrimitive.content
        val sha =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(content.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        assertEquals(sha, output.getValue("sha256").jsonPrimitive.content)
        return content
    }

    private fun invoke(
        name: String,
        args: JsonObject,
    ): JsonObject {
        val result = invokeRaw(name, args)
        assertTrue("$name failed: $result", result is ToolExecutorResult.Completed)
        return (result as ToolExecutorResult.Completed).output.jsonObject
    }

    private fun invokeRaw(
        name: String,
        args: JsonObject,
    ): ToolExecutorResult {
        val registry =
            ApplicationProvider
                .getApplicationContext<HelixApplication>()
                .appContainer.toolPipeline.registry
        val descriptor = requireNotNull(registry.resolveLatest(ToolName(name)))
        assertEquals(ToolSchemaValidation.Valid, ToolSchemaValidator.validate(descriptor.inputSchema, args))
        return registry.executor(descriptor.name, descriptor.version).execute(
            ExecutableToolCall(
                toolCallId = "helix-docs-$name",
                toolName = name,
                toolVersion = descriptor.version.value.toString(),
                args = args,
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                deadline = Instant.now().plusSeconds(30),
                cancel = NoCancellation,
            ),
        )
    }
}
