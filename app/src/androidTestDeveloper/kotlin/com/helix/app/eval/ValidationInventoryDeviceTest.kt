package com.helix.app.eval

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import org.junit.Test

/** Read-only owner-authorized inventory; never exports credentials, endpoints or conversation contents. */
class ValidationInventoryDeviceTest {
    @Test fun reportInstalledRoomIdentityOnly() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        android.database.sqlite.SQLiteDatabase
            .openDatabase(
                app.getDatabasePath("helix.db").path,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                db.rawQuery("SELECT identity_hash FROM room_master_table WHERE id=42", null).use { rows ->
                    check(rows.moveToFirst())
                    report("VALIDATION_ROOM_IDENTITY=${rows.getString(0)}")
                }
            }
    }

    @Test fun reportLatestSyntheticJobFailure() {
        val storage = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer.storage
        val turn =
            storage.turns.recent(10).firstOrNull {
                storage.sessions.resolve(it.sessionId).title == "Live original Job fixture"
            } ?: error("Synthetic live Job fixture missing")
        report("LIVE_DIAGNOSTIC state=${turn.state} error=${turn.errorCode}")
        storage.modelCalls.listByTurn(turn.id).forEach { report("LIVE_MODEL state=${it.state} usage=${it.usage}") }
        storage.toolCalls.listByTurn(turn.id).forEach { call ->
            val codes =
                storage.auditEvents
                    .listByCorrelation(call.callId)
                    .filter { it.type == "tool_dispatch" }
                    .map {
                        kotlinx.serialization.json.Json
                            .parseToJsonElement(it.redactedPayload)
                            as kotlinx.serialization.json.JsonObject
                    }.map { it["code"]?.toString() }
            report("LIVE_TOOL name=${call.name} state=${call.state} codes=$codes")
            if (call.name == "tools.search") {
                val search =
                    kotlinx.serialization.json.Json
                        .parseToJsonElement(call.argsJson)
                        as kotlinx.serialization.json.JsonObject
                report("LIVE_DISCOVERY query=${search["query"]} limit=${search["limit"]}")
            }
        }
    }

    @Test fun reportCurrentModelIdentityAndActiveCount() {
        val storage = ApplicationProvider.getApplicationContext<HelixApplication>().appContainer.storage
        storage.providerConfigs.list().forEach { provider ->
            report("VALIDATION_PROVIDER id=${provider.id} protocol=${provider.protocol} model=${provider.model}")
            val uri = provider.endpoint?.let(android.net.Uri::parse)
            if (uri?.host in setOf("127.0.0.1", "10.0.2.2", "localhost")) {
                report("VALIDATION_LOCAL_PORT=${uri?.port} hasSecret=${provider.secretAlias != null}")
            }
        }
        val active =
            storage.sessions.list().sumOf { session ->
                storage.turns.listBySession(session.id).count {
                    it.state !in setOf("COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED", "NEEDS_REVIEW")
                }
            }
        report("VALIDATION_ACTIVE_TURNS=$active")
    }

    private fun report(value: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            android.os.Bundle().apply { putString("stream", "$value\n") },
        )
    }
}
