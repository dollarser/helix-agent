package com.helix.app.runtimebridge

import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.proot.ensureInstalledRuntime
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolDispatchOutcome
import com.helix.core.model.TurnState
import com.helix.core.policy.SessionPermissionConfig
import com.helix.extensions.skills.SkillEnablementScope
import com.helix.extensions.skills.SkillKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Existing runtime only. Synthetic local scripts/time reads, no external credentials or accounts. */
class ScriptLinuxBridgeDeviceTest {
    @Suppress("LongMethod") // One original task owns settings, imports, resources and cleanup throughout the journey.
    @Test
    fun pythonNodeAndPinnedSkillsUseOriginalWorkspaceAndPersistDelegatedCalls() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val container = app.appContainer
        val storage = container.storage
        clearBridgeTestSessions(app)
        check(storage.turns.listActive().isEmpty())
        ensureInstalledRuntime(app)
        val settings = HostToolBridgeSettings(app)
        val enabled = settings.enabled

        val identity = UUID.randomUUID().toString()
        val session = "script-linux-$identity"
        val name = "script-$identity"
        val cwd = "bridge-fixture-$identity"
        val workspace = File(app.filesDir, "workspaces/app/$cwd").apply { mkdirs() }
        val source = File(app.cacheDir, name).apply { mkdirs() }
        val keys = mutableListOf<SkillKey>()
        val now = System.currentTimeMillis()
        storage.sessions.create(session, "Synthetic script Linux", null, null, now)
        storage.turns.start(session, session, now)
        advanceBridgeTestTurn(app, session)
        storage.sessionPermissionConfigs.setForSession(
            session,
            SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
            now,
        )
        var sequence = 0

        fun dispatch(
            tool: String,
            args: JsonObject,
        ) = container.toolExecution.dispatchToolCall("$session-${++sequence}", session, tool, args.toString())

        fun linux(vararg argv: String): JsonObject =
            succeeded(
                dispatch(
                    "code.linux.run",
                    buildJsonObject {
                        put("argv", JsonArray(argv.map(::JsonPrimitive)))
                        put("workspace", "scope:app:")
                        put("cwd", cwd)
                        put("toolBridge", true)
                    },
                ),
            )
        try {
            check(container.profileStore.profile == com.helix.core.model.SafetyProfile.ADVANCED)
            settings.setEnabled(true)
            val python = linux("python3", "-c", pythonSource)
            assertTrue(
                python
                    .getValue("stdout")
                    .jsonPrimitive.content
                    .contains("PYTHON_BRIDGE_OK"),
            )
            val node = linux("node", "--input-type=module", "-e", nodeSource)
            assertTrue(
                node
                    .getValue("stdout")
                    .jsonPrimitive.content
                    .contains("NODE_BRIDGE_OK"),
            )
            File(source, "scripts").mkdirs()
            File(source, "SKILL.md").writeText("---\nname: $name\ndescription: Synthetic fixture\n---\nFixture")
            File(source, "scripts/run.py").writeText(skillSource)
            File(source, "scripts/missing.py").writeText("open('must-not-run.txt','x').write('bad')")
            File(source, "scripts/manifest.json").writeText(manifestSource)
            val snapshot =
                container.skillImportService.commit(
                    container.skillImportService.stageDirectory(source.toPath()),
                    File(app.filesDir, "skills/snapshots").toPath(),
                )
            val key = container.skillRepository.registerSnapshot(snapshot).also(keys::add)
            container.skillRepository.setEnabled(key, true, SkillEnablementScope.SESSION, session)

            fun skill(path: String) =
                buildJsonObject {
                    put("source", key.source.name)
                    put("name", key.name)
                    put("snapshotHash", key.snapshotHash)
                    put("path", path)
                    put("workspace", "scope:app:")
                    put("cwd", cwd)
                    put("args", JsonArray(listOf("", "a; echo no", "中文").map(::JsonPrimitive)))
                }
            val good = succeeded(dispatch("skills.run", skill("scripts/run.py")))
            assertTrue(
                good
                    .getValue("stdout")
                    .jsonPrimitive.content
                    .contains("SKILL_OK"),
            )
            assertEquals("original-workspace", File(workspace, "skill.txt").readText())
            val missing = dispatch("skills.run", skill("scripts/missing.py"))
            assertTrue(missing.toString(), missing is ToolDispatchOutcome.ExecutionFailed)
            assertFalse(File(workspace, "must-not-run.txt").exists())
            assertEquals(
                2,
                storage.toolCalls.listByTurn(session).count {
                    it.name == "time.now" &&
                        it.state == "COMPLETED"
                },
            )
            assertFalse(File(snapshot.directory.toFile(), "skill.txt").exists())
        } finally {
            settings.setEnabled(enabled)
            storage.deleteSessionPermanently(session)
            keys.forEach(container.skillRepository::removePermanentlyForPrivacy)
            source.deleteRecursively()
            workspace.deleteRecursively()
        }
    }

    private fun succeeded(outcome: ToolDispatchOutcome): JsonObject {
        assertTrue(outcome.toString(), outcome is ToolDispatchOutcome.Succeeded)
        return Json.parseToJsonElement((outcome as ToolDispatchOutcome.Succeeded).result.payload).jsonObject
    }

    private val pythonSource =
        """
        import helix
        r=helix.call('time.now',{})
        assert r['status']=='SUCCEEDED'
        print('PYTHON_BRIDGE_OK')
        """.trimIndent()
    private val nodeSource =
        """
        import {call} from '/tmp/helix.mjs';
        const r=await call('time.now',{});
        if(r.status!=='SUCCEEDED') throw Error('failed');
        console.log('NODE_BRIDGE_OK',process.version);
        """.trimIndent()
    private val skillSource =
        """
        import sys
        assert sys.argv[1:]==['','a; echo no','中文']
        open('skill.txt','x').write('original-workspace')
        print('SKILL_OK')
        """.trimIndent()
    private val manifestSource =
        """
        {"scripts":{
          "scripts/run.py":{"python":["json"]},
          "scripts/missing.py":{"commands":["helix-missing-fixture-command"]}
        }}
        """.trimIndent()
}
