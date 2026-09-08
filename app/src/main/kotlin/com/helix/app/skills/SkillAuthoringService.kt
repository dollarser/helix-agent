package com.helix.app.skills

import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.extensions.skills.SkillImportPreview
import com.helix.extensions.skills.SkillImportService
import com.helix.extensions.skills.SkillLoader
import com.helix.extensions.skills.SkillSource
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/** User-created drafts stay in Workspace; validation never installs or enables a Skill. */
class SkillAuthoringService(
    private val store: WorkspaceArtifactStore,
    private val importer: SkillImportService,
    temporaryRoot: Path,
) {
    private val source = WorkspaceImportSource(store, temporaryRoot)

    fun importArchive(input: java.io.InputStream): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= 16L * 1024 * 1024) { "SKILL_ARCHIVE_TOO_LARGE" }
            output.write(buffer, 0, count)
        }
        val path = FileScopePath("app", "input/skill-${java.util.UUID.randomUUID()}.zip")
        store.writeArtifact(path, output.toByteArray(), "input")
        return path.toModelReference()
    }

    fun loadDraft(path: String): SkillDraft =
        source.capture(path, { false }) { captured ->
            require(Files.isDirectory(captured)) { "SKILL_DIRECTORY_REQUIRED" }
            val document = SkillLoader().load(captured, SkillSource.USER_IMPORTED)
            SkillDraft(
                document.catalogEntry.name,
                document.catalogEntry.description,
                document.body,
                document.catalogEntry.contentHash,
                document.rawContent,
            )
        }

    fun preview(
        path: String,
        cancelled: () -> Boolean = { false },
    ): SkillImportPreview = withStaged(path, cancelled) { it.preview }

    internal fun <T> withStaged(
        path: String,
        cancelled: () -> Boolean,
        use: (com.helix.extensions.skills.StagedSkillImport) -> T,
    ): T =
        source.capture(path, cancelled) { captured ->
            val staged =
                if (Files.isDirectory(captured)) {
                    importer.stageDirectory(captured)
                } else {
                    importer.stageZip(captured)
                }
            try {
                check(!cancelled()) { "IMPORT_CANCELLED" }
                use(staged)
            } finally {
                importer.discard(staged)
            }
        }

    @Synchronized
    fun saveEditedDraft(
        path: String,
        manifest: String,
        expectedHash: String,
    ): String {
        val reference = FileScopePath.fromModelReference(path)
        require(reference.scopeId == "app" && reference.relativePath.startsWith("work/skills/")) {
            "SKILL_DRAFT_PATH_REQUIRED"
        }
        val bytes = manifest.toByteArray()
        require(bytes.size <= 64 * 1024) { "SKILL_INVALID_BODY" }
        source.capture(path, { false }) { captured ->
            Files.write(captured.resolve("SKILL.md"), bytes)
            SkillLoader().load(captured, SkillSource.USER_IMPORTED)
        }
        store.writeArtifact(FileScopePath("app", "${reference.relativePath}/SKILL.md"), bytes, "work", expectedHash)
        return path
    }

    @Synchronized
    fun saveDraft(
        name: String,
        description: String,
        body: String,
    ): String {
        require(Regex("[a-z0-9]+(?:-[a-z0-9]+)*").matches(name) && name.length <= 64) { "SKILL_INVALID_NAME" }
        require(description.isNotBlank() && description.length <= 1024) { "SKILL_INVALID_DESCRIPTION" }
        val bytes = "---\nname: $name\ndescription: ${JsonPrimitive(description)}\n---\n$body\n".toByteArray()
        require(bytes.size <= 64 * 1024 && body.isNotBlank()) { "SKILL_INVALID_BODY" }
        val path = FileScopePath("app", "work/skills/$name/SKILL.md")
        require(!store.stat(path).exists) { "SKILL_DRAFT_EXISTS" }
        if (!store.stat(path.parent).exists) store.mkdir(path.parent, "work")
        store.writeArtifact(path, bytes, "work")
        return FileScopePath("app", "work/skills/$name").toModelReference()
    }
}

data class SkillDraft(
    val name: String,
    val description: String,
    val body: String,
    val contentHash: String,
    val manifest: String,
)
