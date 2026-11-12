package com.helix.extensions.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class SkillRepositoryTest {
    @Test fun pluginContentFollowsOwnerAndCannotBeSelectedIndependently() {
        val roots = roots()
        val document = SkillLoader().loadBuiltIn("---\nname: guide\ndescription: Guide\n---\nWorkflow", "guide")
        var selected = true
        val repository =
            SkillRepository(
                roots.snapshots,
                roots.state,
                roots.trash,
                contributedDocuments = { session ->
                    if (session == null ||
                        (session == "a" && selected)
                    ) {
                        listOf(document)
                    } else {
                        emptyList()
                    }
                },
            )
        val key = repository.list().single { it.key.name == "guide" }.key
        assertTrue(repository.listSelectable("a").none { it.key == key })
        assertTrue(repository.listForModel("a", null).any { it.key == key && it.enabled })
        assertFalse(repository.list("b").single { it.key == key }.enabled)
        for (enabled in listOf(true, false)) {
            assertThrows(IllegalArgumentException::class.java) {
                repository.setEnabled(key, enabled, SkillEnablementScope.SESSION, "a")
            }
            assertThrows(IllegalArgumentException::class.java) {
                repository.setEnabled(key, enabled, SkillEnablementScope.GLOBAL)
            }
        }
        selected = false
        assertThrows(IllegalArgumentException::class.java) { repository.read(key, "a") }
        selected = true
        assertEquals("Workflow", repository.read(key, "a").body.trim())
        assertTrue(repository.listSelectable("a").any { it.key.name == "repo-inspection" })
    }

    @Test fun identicalPluginAndIndependentContentHaveSeparateFilesAndEnablement() {
        val roots = roots()
        val source = importedSource(roots.root)
        val importer = SkillImportService(roots.staging)
        var selected = true
        val repository =
            SkillRepository(
                roots.snapshots,
                roots.state,
                roots.trash,
                builtIns = emptyList(),
                pluginAvailable = { key, session -> key.ownerId == "plugin-a" && selected && session == "a" },
            )
        val standalone = repository.registerSnapshot(importer.commit(importer.stageDirectory(source), roots.snapshots))
        val plugin =
            repository.registerSnapshot(
                importer.commit(importer.stageDirectory(source), roots.snapshots, "plugin-a"),
            )
        val other =
            repository.registerSnapshot(
                importer.commit(importer.stageDirectory(source), roots.snapshots, "plugin-b"),
            )
        assertEquals(standalone.snapshotHash, plugin.snapshotHash)
        assertEquals(plugin.snapshotHash, other.snapshotHash)
        assertEquals(3, setOf(standalone, plugin, other).size)
        assertEquals(listOf(standalone), repository.listSelectable("a").map { it.key })
        assertFalse(repository.listSelectable("a").single().enabled)
        assertTrue(repository.read(plugin, "a").body.contains("Imported body"))
        assertThrows(IllegalArgumentException::class.java) { repository.read(standalone, "a") }
        assertThrows(IllegalArgumentException::class.java) { repository.read(other, "a") }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { repository.setEnabled(plugin, true, SkillEnablementScope.GLOBAL) }
        assertThrows(IllegalArgumentException::class.java) { repository.uninstall(plugin) }
        repository.setEnabled(standalone, true, SkillEnablementScope.SESSION, "a")
        val independentDirectory = repository.scriptSnapshot(standalone, "a", null).directory
        val pluginDirectory = repository.scriptSnapshot(plugin, "a", null).directory
        assertFalse(Files.isSameFile(independentDirectory, pluginDirectory))
        selected = false
        assertThrows(IllegalArgumentException::class.java) { repository.read(plugin, "a") }
        assertTrue(repository.read(standalone, "a").body.contains("Imported body"))
        val restarted = SkillRepository(roots.snapshots, roots.state, roots.trash, builtIns = emptyList())
        assertEquals(setOf(plugin, other), restarted.pluginSnapshotKeys().toSet())
        assertEquals(listOf(standalone), restarted.listSelectable("a").map { it.key })
        assertTrue(restarted.read(standalone, "a").body.contains("Imported body"))
        restarted.removePermanentlyForPrivacy(plugin)
        assertTrue(restarted.hasSnapshot(other))
        assertTrue(restarted.hasSnapshot(standalone))
        restarted.uninstall(standalone)
        assertFalse(restarted.hasSnapshot(standalone))
        assertTrue(restarted.hasSnapshot(other))
    }

    @Test fun changingOneOwnerFileDoesNotChangeOtherOwnerAndFailsHashValidation() {
        val roots = roots()
        val source = importedSource(roots.root)
        val importer = SkillImportService(roots.staging)
        val repository =
            SkillRepository(
                roots.snapshots,
                roots.state,
                roots.trash,
                builtIns = emptyList(),
                pluginAvailable = { _, _ -> true },
            )
        val first =
            repository.registerSnapshot(
                importer.commit(importer.stageDirectory(source), roots.snapshots, "plugin-a"),
            )
        val second =
            repository.registerSnapshot(
                importer.commit(importer.stageDirectory(source), roots.snapshots, "plugin-b"),
            )
        val firstDirectory = repository.scriptSnapshot(first, null, null).directory
        Files.writeString(firstDirectory.resolve("references/guide.txt"), "changed")
        assertThrows(IllegalArgumentException::class.java) { repository.scriptSnapshot(first, null, null) }
        assertEquals("guide", repository.readResource(second, "references/guide.txt").content)
    }

    @Test fun expertEnablementIsSessionScopedAndCannotOverrideSourceOrExplicitSessionDisable() {
        val roots = roots()
        var linked = true
        var available = true
        val repository =
            SkillRepository(
                roots.snapshots,
                roots.state,
                roots.trash,
                sourceAvailable = { _, _ -> available },
                expertEnablement = { key, session -> linked && session == "a" && key.name == "repo-inspection" },
            )
        val key = repository.list().single { it.key.name == "repo-inspection" }.key
        repository.setEnabled(key, false, SkillEnablementScope.GLOBAL)
        assertFalse(repository.list().single { it.key == key }.enabled)
        assertTrue(repository.list("a").single { it.key == key }.enabled)
        assertFalse(repository.list("b").single { it.key == key }.enabled)
        assertTrue(repository.read(key, "a").body.isNotBlank())
        repository.setEnabled(key, false, SkillEnablementScope.SESSION, "a")
        assertThrows(IllegalArgumentException::class.java) { repository.read(key, "a") }
        repository.setEnabled(key, true, SkillEnablementScope.SESSION, "a")
        linked = false
        assertTrue(repository.list("a").single { it.key == key }.enabled)
        available = false
        assertThrows(IllegalArgumentException::class.java) { repository.read(key, "a") }
    }

    @Test fun nativeReferenceReadsUseTheSameSnapshotEligibilityAndBounds() {
        val root = Files.createTempDirectory("native-references")
        val manifest = "---\nname: guide\ndescription: Guide\n---\nShort entry"
        val inputs = linkedMapOf("references/guide.txt" to "参考")
        val loader = SkillLoader()
        val document = loader.loadBuiltIn(manifest, "guide", inputs)
        inputs["references/guide.txt"] = "mutated caller"
        var documents = listOf(document)
        var available = true
        val repository =
            SkillRepository(
                root.resolve("snapshots"),
                root.resolve("state"),
                root.resolve("trash"),
                builtIns = emptyList(),
                resourceLimitBytes = 6,
                sourceAvailable = { _, _ -> available },
                contributedDocuments = { documents },
            )
        val key = repository.list("s").single().key
        val resource = repository.readResource(key, "references/guide.txt", "s")
        assertEquals("参考", resource.content)
        assertEquals(6L, resource.sizeBytes)
        assertEquals("utf-8", resource.encoding)
        assertThrows(IllegalArgumentException::class.java) { repository.readResource(key, "../guide.txt", "s") }
        assertThrows(IllegalArgumentException::class.java) { repository.readResource(key, "scripts/run", "s") }
        assertThrows(IllegalArgumentException::class.java) { repository.readResource(key, "references/missing", "s") }
        available = false
        assertThrows(IllegalArgumentException::class.java) { repository.readResource(key, "references/guide.txt", "s") }
        available = true
        assertThrows(IllegalArgumentException::class.java) {
            repository.setEnabled(key, false, SkillEnablementScope.SESSION, "s")
        }
        assertEquals("参考", repository.readResource(key, "references/guide.txt", "s").content)
        documents = listOf(loader.loadBuiltIn(manifest, "guide", mapOf("references/guide.txt" to "changed")))
        val changed = repository.list("s").single().key
        assertTrue(changed.snapshotHash != key.snapshotHash)
        assertThrows(IllegalArgumentException::class.java) { repository.readResource(key, "references/guide.txt", "s") }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { repository.readResource(changed, "references/guide.txt", "s") }
        documents = emptyList()
        assertTrue(repository.list("s").isEmpty())
        assertThrows(
            IllegalArgumentException::class.java,
        ) { repository.readResource(changed, "references/guide.txt", "s") }
    }

    @Test fun nativeReferenceIdentityMatchesTheFilesystemSnapshotAndRejectsUnsafeNames() {
        val root = Files.createTempDirectory("native-reference-identity")
        val manifest = "---\nname: guide\ndescription: Guide\n---\nShort entry"
        val directory = root.resolve("guide")
        Files.createDirectories(directory.resolve("references"))
        Files.writeString(directory.resolve("SKILL.md"), manifest)
        Files.writeString(directory.resolve("references/guide.txt"), "reference")
        val loader = SkillLoader()
        val document = loader.loadBuiltIn(manifest, "guide", mapOf("references/guide.txt" to "reference"))
        assertEquals(
            SkillSnapshotInspector(SkillImportLimits(), loader)
                .inspect(directory, SkillSource.BUILT_IN)
                .snapshotHash,
            document.catalogEntry.contentHash,
        )
        listOf("/references/a", "references/../a", "references/a\\b", "scripts/../a", "references//a").forEach { path ->
            assertThrows(
                IllegalArgumentException::class.java,
            ) { loader.loadBuiltIn(manifest, "guide", mapOf(path to "x")) }
        }
    }

    @Test fun changedHostContentInvalidatesOldKeyWithoutOverwritingSameNameContent() {
        val root = Files.createTempDirectory("host-skill-revisions")
        val loader = SkillLoader()

        fun document(body: String) = loader.loadBuiltIn("---\nname: guide\ndescription: Guide\n---\n$body", "guide")
        var documents = listOf(document("first"), document("other owner"))
        val repository =
            SkillRepository(
                root.resolve("snapshots"),
                root.resolve("state"),
                root.resolve("trash"),
                builtIns = emptyList(),
                contributedDocuments = { documents },
            )
        val old = repository.list("s").map { it.key }
        assertEquals(2, old.size)
        assertEquals(2, old.map { it.snapshotHash }.distinct().size)
        documents = listOf(document("replacement"))
        assertThrows(IllegalArgumentException::class.java) { repository.read(old.first(), "s") }
        assertEquals("replacement", repository.read(repository.list("s").single().key, "s").body.trim())
    }

    @Test fun disabledSkillCanBeInspectedButCannotBeReadForExecution() {
        val root =
            java.nio.file.Files
                .createTempDirectory("inspect-skill")
        val repository = SkillRepository(root.resolve("snapshots"), root.resolve("state"), root.resolve("trash"))
        val key = repository.list().first().key
        repository.setEnabled(key, false, SkillEnablementScope.GLOBAL)
        assertTrue(repository.inspect(key).rawContent.contains("name: ${key.name}"))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { repository.read(key) }
        assertTrue(!repository.list().single { it.key == key }.enabled)
    }

    @Test
    fun `built-ins default globally enabled and session override does not change global state`() {
        val roots = roots()
        val repository = repository(roots)
        val key = repository.list().single { it.key.name == "repo-inspection" }.key

        assertTrue(repository.list().single { it.key == key }.enabled)
        repository.setEnabled(key, false, SkillEnablementScope.SESSION, sessionId = "session-a")

        assertFalse(repository.list("session-a").single { it.key == key }.enabled)
        assertTrue(repository.list("session-b").single { it.key == key }.enabled)
        assertThrows(IllegalArgumentException::class.java) { repository.read(key, "session-a") }
        assertTrue(repository.read(key, "session-b").body.contains("Repository inspection"))
    }

    @Test
    fun `session disable blocks reading but does not isolate discovery metadata`() {
        val roots = roots()
        val source = importedSource(roots.root)
        val sentinel = "SYNTHETIC_PRIVATE_CATALOG_SENTINEL"
        val manifest = source.resolve("SKILL.md")
        Files.writeString(manifest, Files.readString(manifest).replace("Imported test skill.", sentinel))
        val importer = SkillImportService(roots.staging)
        val snapshot = importer.commit(importer.stageDirectory(source), roots.snapshots)
        val repository = repository(roots)
        val key = repository.registerSnapshot(snapshot)
        repository.setEnabled(key, false, SkillEnablementScope.SESSION, "synthetic-session")

        // Enablement is execution control, not a private-context isolation boundary.
        val discovered = repository.listForModel("synthetic-session", null).single { it.key == key }
        assertFalse(discovered.enabled)
        assertEquals(sentinel, discovered.description)
        assertThrows(IllegalArgumentException::class.java) { repository.read(key, "synthetic-session") }
    }

    @Test
    fun `global enablement persists across repository recreation`() {
        val roots = roots()
        val repository = repository(roots)
        val key = repository.list().single { it.key.name == "web-research" }.key
        repository.setEnabled(key, false, SkillEnablementScope.GLOBAL)

        val recreated = repository(roots)

        assertFalse(recreated.list().single { it.key == key }.enabled)
    }

    @Test
    fun `session enablement persists across repository recreation without changing global state`() {
        val roots = roots()
        val repository = repository(roots)
        val key = repository.list().single { it.key.name == "repo-inspection" }.key
        repository.setEnabled(key, false, SkillEnablementScope.SESSION, "session-a")

        val recreated = repository(roots)

        assertFalse(recreated.list("session-a").single { it.key == key }.enabled)
        assertTrue(recreated.list("session-b").single { it.key == key }.enabled)
        assertTrue(recreated.list().single { it.key == key }.enabled)
    }

    @Test
    fun `imported snapshot defaults disabled and reads only bounded references and assets`() {
        val roots = roots()
        val source = importedSource(roots.root)
        val importService = SkillImportService(roots.staging)
        val snapshot = importService.commit(importService.stageDirectory(source), roots.snapshots)
        val repository = repository(roots)
        val key = repository.registerSnapshot(snapshot)

        assertFalse(repository.list().single { it.key == key }.enabled)
        assertThrows(IllegalArgumentException::class.java) { repository.read(key) }
        repository.setEnabled(key, true, SkillEnablementScope.SESSION, "session")
        assertTrue(repository.read(key, "session").body.contains("Imported body"))

        val reference = repository.readResource(key, "references/guide.txt", "session")
        assertEquals("utf-8", reference.encoding)
        assertEquals("guide", reference.content)
        assertEquals(64, reference.sha256.length)
        val asset = repository.readResource(key, "assets/blob.bin", "session")
        assertEquals("base64", asset.encoding)

        val script = repository.readResource(key, "scripts/run.js", "session")
        assertTrue(script.content.contains("must not auto-run"))
        assertEquals(listOf("scripts/run.js"), repository.scripts(key, "session", null).map { it.relativePath })
        assertEquals(key.snapshotHash, repository.scriptSnapshot(key, "session", null).snapshotHash)
        write(snapshot.directory, "scripts/run.js", "changed".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { repository.scripts(key, "session", null) }
        assertThrows(IllegalArgumentException::class.java) { repository.readResource(key, "scripts/run.js", "session") }
        listOf("../outside", "/absolute", "scripts/../run.js", "references/link/escape")
            .forEach { path ->
                assertThrows(IllegalArgumentException::class.java) {
                    repository.readResource(key, path, "session")
                }
            }
    }

    @Test
    fun `remove moves only imported snapshot to recoverable trash`() {
        val roots = roots()
        val source = importedSource(roots.root)
        val importService = SkillImportService(roots.staging)
        val snapshot = importService.commit(importService.stageDirectory(source), roots.snapshots)
        val repository = repository(roots)
        val key = repository.registerSnapshot(snapshot)

        val trash = repository.remove(key)

        assertFalse(Files.exists(snapshot.directory))
        assertTrue(Files.exists(trash.resolve("SKILL.md")))
        assertFalse(repository.list().any { it.key == key })
        val builtIn = repository.list().first { it.key.source == SkillSource.BUILT_IN }.key
        assertThrows(IllegalArgumentException::class.java) { repository.remove(builtIn) }
    }

    private fun repository(roots: Roots): SkillRepository = SkillRepository(roots.snapshots, roots.state, roots.trash)

    private fun importedSource(root: Path): Path {
        val directory = Files.createDirectory(root.resolve("imported-skill"))
        Files.writeString(
            directory.resolve("SKILL.md"),
            """
            ---
            name: imported-skill
            description: Imported test skill.
            allowed-tools: Bash(root:*)
            ---
            # Imported body
            """.trimIndent(),
        )
        write(directory, "references/guide.txt", "guide".toByteArray())
        write(directory, "assets/blob.bin", byteArrayOf(0xC3.toByte(), 0x28))
        write(directory, "scripts/run.js", "throw new Error('must not auto-run')".toByteArray())
        return directory
    }

    private fun write(
        root: Path,
        relative: String,
        bytes: ByteArray,
    ) {
        val target = root.resolve(relative)
        Files.createDirectories(target.parent)
        Files.write(target, bytes)
    }

    private fun roots(): Roots {
        val root = Files.createTempDirectory("skill-repository")
        return Roots(
            root = root,
            staging = root.resolve("staging"),
            snapshots = root.resolve("snapshots"),
            state = root.resolve("state/enablement.txt"),
            trash = root.resolve("trash"),
        )
    }

    private data class Roots(
        val root: Path,
        val staging: Path,
        val snapshots: Path,
        val state: Path,
        val trash: Path,
    )
}
