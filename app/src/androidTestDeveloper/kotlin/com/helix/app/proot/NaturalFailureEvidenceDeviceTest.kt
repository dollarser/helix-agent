package com.helix.app.proot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.helix.core.model.ToolAvailabilityScope
import com.helix.core.model.ToolAvailabilityState
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.repository.ToolAvailabilityRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Offline checks for this fixture's evidence, not a production goal verifier or model quality claim. */
class NaturalFailureEvidenceDeviceTest {
    @Test fun diagnosisThenRepairRestoresOnlyOwnedSessionAvailability() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = "natural-availability-${UUID.randomUUID()}"
        val directory = File(context.noBackupFilesDir, database).apply { check(mkdirs()) }
        val storage = HelixStorage.open(context, database, directory)
        try {
            val repository = storage.toolAvailability
            val session = "fixture"
            val source = "helix"
            seedAvailability(repository, source, session)
            val identities = (naturalFailureAllowedTools(true) + "bash").map { source to it }
            val before = identities.associateWith { (origin, name) -> repository.byTool(origin, name) }
            val scoped = NaturalFixtureAvailability(repository, identities, session)
            try {
                storage.sessions.create(session, "Owned offline fixture", null, null, 1)
                scoped.configure(naturalFailureAllowedTools(false))
                assertEquals(
                    ToolAvailabilityState.DISABLED,
                    repository.statesFor(source, "read", session, null).session,
                )
                // Match production case boundaries: deleting the session does not remove availability rows.
                storage.deleteSessionPermanently(session)
                storage.sessions.create(session, "Owned offline fixture", null, null, 2)
                scoped.configure(naturalFailureAllowedTools(true))
                naturalFailureAllowedTools(true).forEach { name ->
                    assertEquals(
                        ToolAvailabilityState.ENABLED,
                        repository.statesFor(source, name, session, null).session,
                    )
                }
                assertEquals(
                    ToolAvailabilityState.DISABLED,
                    repository.statesFor(source, "bash", session, null).session,
                )
                assertEquals(
                    ToolAvailabilityState.DISABLED,
                    repository.statesFor(source, "write", session, null).global,
                )
            } finally {
                scoped.restore()
            }
            identities.forEach { identity ->
                val original = before.getValue(identity)
                val after = repository.byTool(identity.first, identity.second)
                // Restoring changes revision/timestamps, never the prior state or any outer/other-session row.
                assertEquals(
                    original.map { Triple(it.scopeKind, it.scopeRef, it.state) },
                    after.map { Triple(it.scopeKind, it.scopeRef, it.state) },
                )
                original.filter { it.scopeKind != "SESSION" || it.scopeRef != session }.forEach {
                    assertTrue(it in after)
                }
            }
        } finally {
            storage.close()
            context.deleteDatabase(database)
            check(directory.deleteRecursively())
        }
    }

    private fun seedAvailability(
        repository: ToolAvailabilityRepository,
        source: String,
        session: String,
    ) {
        repository.set(source, "read", ToolAvailabilityScope.SESSION, session, ToolAvailabilityState.DISABLED, 1)
        repository.set(source, "write", ToolAvailabilityScope.GLOBAL, "", ToolAvailabilityState.DISABLED, 1)
        repository.set(source, "edit", ToolAvailabilityScope.SESSION, "other", ToolAvailabilityState.DISABLED, 1)
        repository.set(
            source,
            "files.stat",
            ToolAvailabilityScope.WORKSPACE,
            "other-workspace",
            ToolAvailabilityState.DISABLED,
            1,
        )
    }

    @Test fun sourceSummaryAndWrongIdentityDoNotCountAsSavedStderr() {
        val marker = NaturalFailureLogTaskDeviceTest.MARKER
        val original =
            buildJsonObject {
                put("source", "saved")
                put("stream", "stderr")
                put("logRef", "turn/call")
                put("exitCode", 7)
                put("content", marker)
            }
        assertTrue(NaturalFailureLogTaskDeviceTest.savedStderr(original, marker))
        for ((key, value) in listOf("source" to "live", "stream" to "stdout", "logRef" to "another/call")) {
            val changed =
                buildJsonObject {
                    original.forEach { (name, field) -> put(name, field) }
                    put(key, value)
                }
            assertFalse(NaturalFailureLogTaskDeviceTest.savedStderr(changed, marker))
        }
        val summary =
            Json
                .parseToJsonElement(
                    """{"contentKind":"tool_result","exitCode":7,"logRef":"turn/call"}""",
                ).jsonObject
        assertFalse(NaturalFailureLogTaskDeviceTest.savedStderr(summary, marker))
    }

    @Test fun markerMustEnterTheActualRequestAsTheMatchingToolResult() {
        val marker = NaturalFailureLogTaskDeviceTest.MARKER
        val binding =
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("id", "provider-wire")
                        put("localId", "owned-local")
                        put("name", "read")
                        put("arguments", "{\"path\":\"scope:ws-owned:document.txt\"}")
                    },
                ),
            )

        fun request(
            role: String = "tool",
            call: String = "provider-wire",
            assistantRole: String = "assistant",
            assistantCall: String = "provider-wire",
            name: String = "read",
            callRef: String = "owned-turn/owned-local",
        ) = buildJsonObject {
            put(
                "messages",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("role", assistantRole)
                            put(
                                "tool_calls",
                                JsonArray(
                                    listOf(
                                        buildJsonObject {
                                            put("id", assistantCall)
                                            put(
                                                "function",
                                                buildJsonObject {
                                                    put("name", name)
                                                    put("arguments", "{\"path\":\"document.txt\"}")
                                                },
                                            )
                                        },
                                    ),
                                ),
                            )
                        },
                        buildJsonObject {
                            put("role", role)
                            put("tool_call_id", call)
                            put("content", "{\"content\":\"$marker\"}\nDelivery facts: {\"callRef\":\"$callRef\"}")
                        },
                    ),
                ),
            )
        }

        fun consumed(
            request: kotlinx.serialization.json.JsonObject,
            local: String = "owned-local",
        ) = NaturalFailureLogTaskDeviceTest.sentToolContent(request, binding, "owned-turn", local, marker)
        // Production uses distinct IDs and projects persisted scoped arguments to a relative request path.
        assertTrue(consumed(request()))
        assertFalse(consumed(request(), "unrelated-local"))
        assertFalse(consumed(request(), "provider-wire"))
        assertFalse(consumed(request(role = "assistant")))
        assertFalse(consumed(request(call = "owned-local")))
        assertFalse(consumed(request(call = "unrelated-wire")))
        assertFalse(consumed(request(assistantRole = "user")))
        assertFalse(consumed(request(assistantCall = "unrelated-wire")))
        assertFalse(consumed(request(name = "unrelated-tool")))
        assertFalse(consumed(request(callRef = "other-turn/owned-local")))
        assertFalse(consumed(request(callRef = "owned-turn/other-local")))
        assertFalse(consumed(request(callRef = "prefix-owned-turn/owned-local")))
        assertFalse(consumed(request(callRef = "owned-turn/owned-local-suffix")))
        assertFalse(consumed(request(callRef = "")))
        assertFalse(
            NaturalFailureLogTaskDeviceTest.sentToolContent(
                request(),
                JsonArray(emptyList()),
                "owned-turn",
                "owned-local",
                marker,
            ),
        )
        assertFalse(
            NaturalFailureLogTaskDeviceTest.sentToolContent(
                request(),
                JsonArray(binding + binding),
                "owned-turn",
                "owned-local",
                marker,
            ),
        )
        val reversed =
            buildJsonObject {
                put("messages", JsonArray((request().getValue("messages") as JsonArray).reversed()))
            }
        assertFalse(consumed(reversed))
    }

    @Test fun markerDigitsAndOtherExitCodesAreNotExitSeven() {
        assertFalse(NaturalFailureLogTaskDeviceTest.exitSeven("SYNTH_NATURAL_ERROR_731"))
        assertFalse(NaturalFailureLogTaskDeviceTest.exitSeven("exitCode=731"))
        assertFalse(NaturalFailureLogTaskDeviceTest.exitSeven("退出码为 70"))
        assertTrue(NaturalFailureLogTaskDeviceTest.exitSeven("退出码为 **7**"))
        assertTrue(NaturalFailureLogTaskDeviceTest.exitSeven("exit code: 7"))
    }

    @Test fun completionRequiresTheActualTargetAndPreservedOtherContent() {
        assertTrue(
            NaturalFailureLogTaskDeviceTest.repairedDocument(
                "label=SYNTH_VALUE\nretain=SYNTH_KEEP_529\n",
                "SYNTH_VALUE",
            ),
        )
        assertFalse(NaturalFailureLogTaskDeviceTest.repairedDocument(null, "SYNTH_VALUE"))
        assertFalse(
            NaturalFailureLogTaskDeviceTest.repairedDocument("label=stale\nretain=SYNTH_KEEP_529\n", "SYNTH_VALUE"),
        )
        assertFalse(NaturalFailureLogTaskDeviceTest.repairedDocument("label=SYNTH_VALUE\n", "SYNTH_VALUE"))
        assertFalse(
            NaturalFailureLogTaskDeviceTest.repairedDocument("label=SYNTH_VALUE\nretain=SYNTH_KEEP_529", "SYNTH_VALUE"),
        )
        assertFalse(
            NaturalFailureLogTaskDeviceTest.repairedDocument(
                "label=SYNTH_VALUE\nretain=SYNTH_KEEP_529\n\n",
                "SYNTH_VALUE",
            ),
        )
    }
}
