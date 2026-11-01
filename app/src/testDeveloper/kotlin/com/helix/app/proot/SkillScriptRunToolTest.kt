package com.helix.app.proot

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.NoCancellation
import com.helix.core.model.ToolName
import com.helix.core.model.ToolVersion
import com.helix.extensions.skills.SkillEnablementScope
import com.helix.extensions.skills.SkillImportService
import com.helix.extensions.skills.SkillRepository
import com.helix.runtime.proot.core.ScriptBundleTree
import com.helix.runtime.proot.ipc.ProotJobCommand
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

class SkillScriptRunToolTest {
    @Suppress("LongMethod") // One pinned import/enable/run/tamper lifecycle shares the same immutable identity.
    @Test
    fun pinnedResourcesAndArgumentArraysUseTheExistingRunnerWithoutSourceWriteback() {
        val root = Files.createTempDirectory("skill-run-fixture")
        val source = root.resolve("demo")
        Files.createDirectories(source.resolve("scripts"))
        Files.writeString(source.resolve("SKILL.md"), "---\nname: demo\ndescription: synthetic\n---\nFixture")
        Files.writeString(source.resolve("scripts/run.py"), "print('synthetic')")
        val imports = SkillImportService(root.resolve("staging"))
        val snapshot = imports.commit(imports.stageDirectory(source), root.resolve("snapshots"))
        val repository = SkillRepository(root.resolve("snapshots"), root.resolve("state/config"), root.resolve("trash"))
        val key = repository.registerSnapshot(snapshot)
        val registry = ToolRegistry()
        val captured = mutableListOf<LinuxRunTool.ParsedLinuxCall>()
        SkillScriptRunTool.register(registry, repository, { call, _ ->
            captured.add(call)
            ToolExecutorResult.Completed(buildJsonObject { put("fixture", true) })
        }, { it })
        val args =
            buildJsonObject {
                put("source", key.source.name)
                put("name", key.name)
                put("snapshotHash", key.snapshotHash)
                put("path", "scripts/run.py")
                put("args", JsonArray(listOf("", "a; echo no", "中文").map(::JsonPrimitive)))
            }
        val call =
            ExecutableToolCall(
                "fixture",
                "skills.run",
                "1",
                args,
                ExecutionTargetType.LOCAL_PROOT,
                Instant.now().plusSeconds(30),
                NoCancellation,
                "session",
                "turn",
            )
        val executor = registry.executor(ToolName("skills.run"), ToolVersion(1))
        assertTrue(executor.execute(call) is ToolExecutorResult.Failed)
        assertTrue(captured.isEmpty())
        repository.setEnabled(key, true, SkillEnablementScope.SESSION, "session")
        assertTrue(executor.execute(call) is ToolExecutorResult.Completed)
        val parsed = captured.single()
        val argv = (parsed.command as ProotJobCommand.Argv).arguments
        assertEquals(listOf("", "a; echo no", "中文"), argv.takeLast(3))
        assertEquals("skills.run", parsed.originalToolName)
        val copied = root.resolve("runtime-copy").toFile()
        ScriptBundleTree.copyVerified(snapshot.directory.toFile(), copied, key.snapshotHash)
        copied.resolve("scripts/run.py").writeText("modified only execution resources")
        assertEquals("print('synthetic')", Files.readString(snapshot.directory.resolve("scripts/run.py")))
        Files.writeString(snapshot.directory.resolve("scripts/run.py"), "changed snapshot")
        assertTrue(executor.execute(call) is ToolExecutorResult.Failed)
        assertEquals(1, captured.size)
        assertThrows(IllegalStateException::class.java) {
            ScriptBundleTree.copyVerified(
                snapshot.directory.toFile(),
                root.resolve("changed-copy").toFile(),
                key.snapshotHash,
            )
        }
        repository.setEnabled(key, false, SkillEnablementScope.SESSION, "session")
        assertFalse(repository.list("session").single { it.key == key }.enabled)
    }
}
