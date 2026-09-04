package com.helix.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolName
import com.helix.core.model.ToolVersion
import com.helix.extensions.skills.SkillCatalogLoader
import com.helix.extensions.skills.SkillSource
import com.helix.extensions.skills.SkillTools
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolSchemaValidation
import com.helix.tools.framework.ToolSchemaValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class SkillToolsDeviceTest {
    @Test
    fun productionSkillToolsImportPageActivateReadResourceAndRemove() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>() as HelixApplication
        val container = app.appContainer
        SkillTools.descriptors().forEach { descriptor ->
            assertNotNull(container.toolPipeline.registry.resolve(descriptor.name, descriptor.version))
        }

        val root = Files.createTempDirectory(app.cacheDir.toPath(), "skill-device-")
        val name = "device-skill-${System.currentTimeMillis()}"
        val source = createSource(root, name)

        val catalog = SkillCatalogLoader().scan(root, SkillSource.USER_IMPORTED)
        assertEquals(listOf(name), catalog.entries.map { it.name })
        assertTrue(catalog.diagnostics.isEmpty())

        val staged = container.skillImportService.stageDirectory(source)
        val installedRoot = app.filesDir.toPath().resolve("skills/snapshots")
        val snapshot = container.skillImportService.commit(staged, installedRoot)
        val key = container.skillRepository.registerSnapshot(snapshot)
        try {
            verifyToolLifecycle(container, key.source.name, key.name, key.snapshotHash)
            assertFalse(Files.exists(snapshot.directory))
        } finally {
            if (container.skillRepository.list().any { it.key == key }) {
                container.skillRepository.remove(key)
            }
        }
    }

    private fun createSource(
        root: java.nio.file.Path,
        name: String,
    ): java.nio.file.Path {
        val source = Files.createDirectory(root.resolve(name))
        Files.write(
            source.resolve("SKILL.md"),
            """
            ---
            name: $name
            description: Device Skill fixture.
            allowed-tools: Bash(root:*)
            ---
            # Device fixture
            """.trimIndent().toByteArray(),
        )
        Files.createDirectories(source.resolve("references"))
        Files.write(source.resolve("references/guide.txt"), "device guide".toByteArray())
        return source
    }

    private fun verifyToolLifecycle(
        container: AppContainer,
        source: String,
        name: String,
        snapshotHash: String,
    ) {
        val firstPage = execute(container, SkillTools.LIST, "{\"limit\":2}")
        assertEquals(2, firstPage.getValue("entries").jsonArray.size)
        assertEquals("false", firstPage.getValue("eof").jsonPrimitive.content)

        val keyArgs =
            "\"source\":\"$source\",\"name\":\"$name\"," +
                "\"snapshotHash\":\"$snapshotHash\""
        execute(
            container,
            SkillTools.ENABLE,
            "{$keyArgs,\"scope\":\"SESSION\",\"sessionId\":\"device-session\"}",
        )
        val read =
            execute(
                container,
                SkillTools.READ,
                "{$keyArgs,\"sessionId\":\"device-session\"}",
            )
        assertEquals("untrusted", read.getValue("trust").jsonPrimitive.content)
        assertTrue(
            read
                .getValue("instructions")
                .jsonPrimitive.content
                .contains("# Device fixture"),
        )

        val resource =
            execute(
                container,
                SkillTools.READ_RESOURCE,
                "{$keyArgs,\"path\":\"references/guide.txt\",\"sessionId\":\"device-session\"}",
            )
        assertEquals("device guide", resource.getValue("content").jsonPrimitive.content)
        assertEquals("utf-8", resource.getValue("encoding").jsonPrimitive.content)

        execute(container, SkillTools.REMOVE, "{$keyArgs}")
    }

    private fun execute(
        container: AppContainer,
        name: String,
        args: String,
    ): JsonObject {
        val descriptor = container.toolPipeline.registry.resolve(ToolName(name), ToolVersion(1))
        val arguments = Json.parseToJsonElement(args).jsonObject
        assertEquals(ToolSchemaValidation.Valid, ToolSchemaValidator.validate(descriptor.inputSchema, arguments))
        val result =
            container.toolPipeline.implementations.resolve(descriptor.name, descriptor.version).execute(
                ExecutableToolCall(
                    toolCallId = "device-$name-${System.nanoTime()}",
                    toolName = name,
                    toolVersion = "1",
                    args = arguments,
                    executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                    deadline = Instant.parse("2030-01-01T00:00:00Z"),
                    cancel = NoCancellation,
                ),
            ) as ToolExecutorResult.Completed
        assertEquals(ToolSchemaValidation.Valid, ToolSchemaValidator.validate(descriptor.outputSchema, result.output))
        return result.output.jsonObject
    }
}
