package com.helix.app.runtimebridge

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.proot.ensureInstalledRuntime
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolDispatchOutcome
import com.helix.core.policy.SessionPermissionConfig
import com.helix.extensions.skills.SkillKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** All content is our synthetic fixture. Installation never executes the included Python script. */
class SkillEntryPathsDeviceTest {
    @Test
    @Suppress("LongMethod") // One task owns all three sources, exact snapshots and cleanup.
    fun createImportAndDownloadBecomeCallableThroughTheExistingDispatcher() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        val c = app.appContainer
        ensureInstalledRuntime(app)
        val id = "skill-entry-${UUID.randomUUID()}"
        val root = "work/$id"
        val owned = File(app.filesDir, "workspaces/app/$root")
        val keys = mutableListOf<SkillKey>()

        val now = System.currentTimeMillis()
        c.storage.sessions.create(id, "Synthetic Skill entry paths", null, null, now)
        c.storage.turns.start(id, id, now)
        advanceBridgeTestTurn(app, id)
        c.storage.sessionPermissionConfigs.setForSession(
            id,
            SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
            now,
        )
        var sequence = 0

        fun call(
            tool: String,
            args: JsonObject,
        ): JsonObject {
            val outcome = c.toolExecution.dispatchToolCall("$id-${++sequence}", id, tool, args.toString())
            assertTrue("$tool: $outcome", outcome is ToolDispatchOutcome.Succeeded)
            return Json.parseToJsonElement((outcome as ToolDispatchOutcome.Succeeded).result.payload).jsonObject
        }

        fun path(value: String) = buildJsonObject { put("path", "scope:app:$value") }

        fun install(source: String): JsonObject {
            val preview = call("skills.preview", buildJsonObject { put("path", source) })
            val result =
                call(
                    "skills.install",
                    buildJsonObject {
                        put("path", source)
                        put("expectedHash", preview.getValue("hash"))
                    },
                )
            val key =
                SkillKey(
                    com.helix.extensions.skills.SkillSource.USER_IMPORTED,
                    result.getValue("name").jsonPrimitive.content,
                    result.getValue("snapshotHash").jsonPrimitive.content,
                )
            keys += key
            assertFalse(
                c.skillRepository
                    .list()
                    .single { it.key == key }
                    .enabled,
            )
            assertTrue(call("skills.list", JsonObject(emptyMap())).toString().contains(key.snapshotHash))
            val identity =
                buildJsonObject {
                    put("source", key.source.name)
                    put("name", key.name)
                    put("snapshotHash", key.snapshotHash)
                }
            call(
                "skills.enable",
                JsonObject(identity + ("scope" to kotlinx.serialization.json.JsonPrimitive("SESSION"))),
            )
            assertTrue(call("skills.read", identity).toString().contains("Synthetic instructions"))
            assertTrue(
                call(
                    "skills.read_resource",
                    JsonObject(
                        identity +
                            ("path" to kotlinx.serialization.json.JsonPrimitive("references/guide.md")),
                    ),
                ).toString()
                    .contains("synthetic reference"),
            )
            assertFalse(File(owned, "executed.txt").exists())
            return identity
        }
        try {
            check(c.profileStore.profile == com.helix.core.model.SafetyProfile.ADVANCED)
            call("files.mkdir", path(root))
            val name = "created-${UUID.randomUUID()}"
            val directory = "$root/.agents/skills/$name"
            call("files.mkdir", path("$directory/references"))
            call("files.mkdir", path("$directory/scripts"))
            content(name).forEach { (relative, text) ->
                call(
                    "write",
                    buildJsonObject {
                        put("path", "scope:app:$directory/$relative")
                        put("content", text)
                    },
                )
            }
            val created = install("scope:app:$directory")
            val imported = "imported-${UUID.randomUUID()}"
            val archive = requireNotNull(c.skillAuthoringService).importArchive(zip(imported).inputStream())
            install(archive)
            val url = requireNotNull(InstrumentationRegistry.getArguments().getString("skill.fixture.url"))
            val downloaded =
                call(
                    "browser.download",
                    buildJsonObject {
                        put("url", url)
                        put("suggestedName", "$id.zip")
                    },
                )
            assertEquals("saved", downloaded.getValue("status").jsonPrimitive.content)
            val reference = downloaded.getValue("reference").jsonPrimitive.content
            install(reference)
            val run =
                call(
                    "skills.run",
                    JsonObject(
                        created +
                            buildJsonObject {
                                put("path", "scripts/run.py")
                                put("workspace", "scope:app:")
                                put("cwd", root)
                            },
                    ),
                )
            assertTrue(run.toString(), run.toString().contains("SKILL_ENTRY_OK"))
            assertEquals("explicit invocation", File(owned, "executed.txt").readText())
        } finally {
            c.toolPipeline.endTurn(id)
            c.storage.deleteSessionPermanently(id)
            keys.forEach(c.skillRepository::removePermanentlyForPrivacy)
            owned.deleteRecursively()
        }
    }

    private fun content(name: String) =
        mapOf(
            "SKILL.md" to "---\nname: $name\ndescription: Synthetic Skill entry\n---\nSynthetic instructions",
            "references/guide.md" to "synthetic reference",
            "scripts/run.py" to "open('executed.txt','x').write('explicit invocation')\nprint('SKILL_ENTRY_OK')",
        )

    private fun zip(name: String): ByteArray =
        ByteArrayOutputStream()
            .also { bytes ->
                ZipOutputStream(bytes).use { zip ->
                    content(name).forEach { (path, text) ->
                        zip.putNextEntry(ZipEntry("repository-revision123/$path"))
                        zip.write(text.toByteArray())
                        zip.closeEntry()
                    }
                }
            }.toByteArray()
}
