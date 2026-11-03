package com.helix.app.skills

import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.extensions.skills.SkillImportService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class SkillAuthoringServiceTest {
    @Test
    fun draftPreviewIsStableAndChangesWithReferenceBytes() =
        fixture { root, service ->
            val path = service.saveDraft("example", "Use for examples", "Read the input and report the result.")
            val first = service.preview(path)
            assertEquals("example", first.name)
            assertEquals(first.snapshotHash, service.preview(path).snapshotHash)
            val reference = root.resolve("workspace/work/skills/example/references/example.txt")
            Files.createDirectories(reference.parent)
            Files.write(reference, "changed".toByteArray())
            assertNotEquals(first.snapshotHash, service.preview(path).snapshotHash)
            assertFalse(Files.exists(root.resolve("snapshots")))
        }

    @Test
    fun duplicateDraftAndInvalidSourcesDoNotOverwrite() =
        fixture { _, service ->
            val path = service.saveDraft("example", "Example", "Body")
            val hash = service.preview(path).snapshotHash
            assertThrows(IllegalArgumentException::class.java) { service.saveDraft("example", "Other", "Changed") }
            assertEquals(hash, service.preview(path).snapshotHash)
            for (invalid in listOf("scope:app:.helix", "scope:other:work/skill", "scope:app:work/../.helix")) {
                assertThrows(IllegalArgumentException::class.java) { service.preview(invalid) }
            }
        }

    @Test
    fun previewToolReturnsActionableManifestValidationWithoutExposingIoDetails() =
        fixture { root, service ->
            val path = service.saveDraft("example", "Example", "Body")
            val registry =
                com.helix.tools.framework
                    .ToolRegistry()
            SkillAuthoringTools.register(registry, service)

            fun preview(
                source: String,
                cancelled: Boolean = false,
            ): com.helix.tools.framework.ToolExecutorResult =
                registry
                    .executor(
                        com.helix.core.model
                            .ToolName("skills.preview"),
                        com.helix.core.model
                            .ToolVersion(1),
                    ).execute(
                        com.helix.tools.framework.ExecutableToolCall(
                            "preview",
                            "skills.preview",
                            "1",
                            kotlinx.serialization.json.buildJsonObject {
                                put("path", kotlinx.serialization.json.JsonPrimitive(source))
                            },
                            com.helix.core.model.ExecutionTargetType.LOCAL_ANDROID,
                            java.time.Instant
                                .now()
                                .plusSeconds(30),
                            object : com.helix.core.model.CancelSignal {
                                override fun isCancelled() = cancelled
                            },
                        ),
                    )
            Files.writeString(root.resolve("workspace/work/skills/example/SKILL.md"), "# Missing frontmatter")
            val invalid = preview(path) as com.helix.tools.framework.ToolExecutorResult.Failed
            assertEquals("SKILL_PREVIEW_FAILED: SKILL.md must start with YAML frontmatter", invalid.detail)
            assertFalse(invalid.detail.contains(root.toString()))
            val missing = preview("scope:app:missing") as com.helix.tools.framework.ToolExecutorResult.Failed
            assertFalse(missing.detail.contains(root.toString()))
            assertFalse(missing.detail.contains("NoSuchFileException"))
            assertEquals(com.helix.tools.framework.ToolExecutorResult.Cancelled, preview(path, cancelled = true))
        }

    @Test
    fun cancellationAndInvalidManifestLeaveNoValidationCopies() =
        fixture { root, service ->
            val path = service.saveDraft("example", "Example", "Body")
            assertThrows(IllegalStateException::class.java) { service.preview(path) { true } }
            Files.write(root.resolve("workspace/work/skills/example/SKILL.md"), "not a skill".toByteArray())
            assertThrows(IllegalArgumentException::class.java) { service.preview(path) }
            Files.list(root.resolve("temporary")).use { assertEquals(0L, it.count()) }
        }

    @Test
    fun editingPreservesMetadataAndRejectsStaleContent() =
        fixture { root, service ->
            val path = service.saveDraft("example", "Example", "Body")
            val file = root.resolve("workspace/work/skills/example/SKILL.md")
            val original =
                String(
                    Files.readAllBytes(file),
                ).replace("description:", "license: MIT\nmetadata:\n  version: '1'\ndescription:")
            Files.write(file, original.toByteArray())
            val draft = service.loadDraft(path)
            service.saveEditedDraft(path, draft.manifest.replace("Body", "New body"), draft.contentHash)
            assertEquals(original.replace("Body", "New body"), service.loadDraft(path).manifest)
            assertThrows(com.helix.core.workspace.PreconditionHashMismatch::class.java) {
                service.saveEditedDraft(path, draft.manifest, draft.contentHash)
            }
            assertEquals(original.replace("Body", "New body"), service.loadDraft(path).manifest)
        }

    @Test
    fun previewRejectsSymlinksAndOversizedInputs() =
        fixture { root, service ->
            val path = service.saveDraft("example", "Example", "Body")
            val link = root.resolve("workspace/work/skills/example/references.txt")
            Files.createSymbolicLink(link, root.resolve("workspace/work/skills/example/SKILL.md"))
            assertThrows(com.helix.core.workspace.SymlinkInPath::class.java) { service.preview(path) }
            Files.delete(link)
            Files.write(link, ByteArray(16 * 1024 * 1024 + 1))
            assertThrows(IllegalArgumentException::class.java) { service.preview(path) }
            Files.list(root.resolve("temporary")).use { assertEquals(0L, it.count()) }
        }

    private fun fixture(test: (Path, SkillAuthoringService) -> Unit) {
        val root = Files.createTempDirectory("authoring-test-")
        try {
            val store = WorkspaceArtifactStore(ScopeRootResolver { root.resolve("workspace") })
            store.ensureLayout("app")
            test(
                root,
                SkillAuthoringService(store, SkillImportService(root.resolve("staging")), root.resolve("temporary")),
            )
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }
}
