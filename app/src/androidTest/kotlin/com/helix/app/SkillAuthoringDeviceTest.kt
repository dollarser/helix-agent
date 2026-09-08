package com.helix.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolName
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolSchemaValidation
import com.helix.tools.framework.ToolSchemaValidator
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class SkillAuthoringDeviceTest {
    @Test
    fun invalidDraftCancellationAndServiceRecreation() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val root = Files.createTempDirectory(app.cacheDir.toPath(), "creator-recovery-")
        val store =
            com.helix.core.workspace.WorkspaceArtifactStore(
                com.helix.core.workspace
                    .ScopeRootResolver { root.resolve("workspace") },
            )
        store.ensureLayout("app")

        fun service() =
            com.helix.app.skills.SkillAuthoringService(
                store,
                com.helix.extensions.skills
                    .SkillImportService(root.resolve("staging")),
                root.resolve("temporary"),
            )
        try {
            val path = service().saveDraft("recovery", "Recovery test", "Read an input")
            val hash = service().preview(path).snapshotHash
            org.junit.Assert.assertThrows(IllegalStateException::class.java) { service().preview(path) { true } }
            assertEquals(hash, service().preview(path).snapshotHash)
            val file = root.resolve("workspace/work/skills/recovery/SKILL.md")
            Files.write(file, "---\nname: recovery\n---\nBody".toByteArray())
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { service().preview(path) }
            Files.list(root.resolve("temporary")).use { assertEquals(0L, it.count()) }
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun actualDraftAndRegisteredPreviewToolRemainUninstalled() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val container = app.appContainer
        val service = requireNotNull(container.skillAuthoringService)
        val name = "authoring-${System.currentTimeMillis()}"
        val path = service.saveDraft(name, "Use for the device test", "Read the input and report its size.")
        try {
            val descriptor = container.toolPipeline.registry.resolve(ToolName("skills.preview"), ToolVersion(1))
            val args = buildJsonObject { put("path", JsonPrimitive(path)) }
            assertEquals(ToolSchemaValidation.Valid, ToolSchemaValidator.validate(descriptor.inputSchema, args))
            val result =
                container.toolPipeline.implementations.resolve(descriptor.name, descriptor.version).execute(
                    ExecutableToolCall(
                        "preview",
                        "skills.preview",
                        "1",
                        args,
                        ExecutionTargetType.LOCAL_ANDROID,
                        Instant.parse("2030-01-01T00:00:00Z"),
                        NoCancellation,
                    ),
                ) as ToolExecutorResult.Completed
            assertEquals(
                ToolSchemaValidation.Valid,
                ToolSchemaValidator.validate(descriptor.outputSchema, result.output),
            )
            assertEquals(
                name,
                result.output.jsonObject
                    .getValue("name")
                    .jsonPrimitive.content,
            )
            assertEquals(
                service.preview(path).snapshotHash,
                result.output.jsonObject
                    .getValue("hash")
                    .jsonPrimitive.content,
            )
            assertFalse(container.skillRepository.list().any { it.key.name == name })
        } finally {
            val root = app.filesDir.toPath().resolve("workspaces/app/work/skills/$name")
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
