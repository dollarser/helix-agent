package com.helix.app.diagnostics

import android.os.Build
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** User-previewable diagnostic artifact. Every field is an explicit, content-free allowlist. */
data class DiagnosticBundlePreview(
    val schemaVersion: Int,
    val sdkInt: Int,
    val supportedAbis: List<String>,
    val process: DiagnosticProcessPreview,
    val exits: List<DiagnosticExitPreview>,
) {
    fun encode(): String =
        buildJsonObject {
            put("schemaVersion", JsonPrimitive(schemaVersion))
            put("sdkInt", JsonPrimitive(sdkInt))
            put("supportedAbis", JsonArray(supportedAbis.map(::JsonPrimitive)))
            put(
                "process",
                buildJsonObject {
                    put("state", JsonPrimitive(process.state))
                    put("heartbeatAtMillis", JsonPrimitive(process.heartbeatAtMillis))
                    put("lastTurnId", process.lastTurnId?.let(::JsonPrimitive) ?: JsonNull)
                    put("lastCorrelationId", process.lastCorrelationId?.let(::JsonPrimitive) ?: JsonNull)
                    put("lastTurnState", process.lastTurnState?.let(::JsonPrimitive) ?: JsonNull)
                    put("crashType", process.crashType?.let(::JsonPrimitive) ?: JsonNull)
                    put("crashFingerprint", process.crashFingerprint?.let(::JsonPrimitive) ?: JsonNull)
                },
            )
            put(
                "exits",
                JsonArray(
                    exits.map { exit ->
                        buildJsonObject {
                            put("reason", JsonPrimitive(exit.reason))
                            put("status", JsonPrimitive(exit.status))
                            put("timestampMillis", JsonPrimitive(exit.timestampMillis))
                            put("importance", JsonPrimitive(exit.importance))
                        }
                    },
                ),
            )
        }.toString().also { encoded ->
            check(encoded.toByteArray(Charsets.UTF_8).size <= MAX_ENCODED_BYTES) {
                "diagnostic preview exceeds $MAX_ENCODED_BYTES bytes"
            }
        }

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_ENCODED_BYTES = 16 * 1024

        fun collect(store: ProcessEvidenceStore): DiagnosticBundlePreview {
            val process = store.read()
            return DiagnosticBundlePreview(
                schemaVersion = SCHEMA_VERSION,
                sdkInt = Build.VERSION.SDK_INT,
                supportedAbis = Build.SUPPORTED_ABIS.take(4),
                process =
                    DiagnosticProcessPreview(
                        state = process.state,
                        heartbeatAtMillis = process.heartbeatAtMillis,
                        lastTurnId = process.lastTurnId,
                        lastCorrelationId = process.lastCorrelationId,
                        lastTurnState = process.lastTurnState,
                        crashType = process.crashType,
                        crashFingerprint = process.crashFingerprint,
                    ),
                exits =
                    store.recentExits().map {
                        DiagnosticExitPreview(it.reason, it.status, it.timestampMillis, it.importance)
                    },
            )
        }
    }
}

data class DiagnosticProcessPreview(
    val state: String,
    val heartbeatAtMillis: Long,
    val lastTurnId: String?,
    val lastCorrelationId: String?,
    val lastTurnState: String?,
    val crashType: String?,
    val crashFingerprint: String?,
)

data class DiagnosticExitPreview(
    val reason: Int,
    val status: Int,
    val timestampMillis: Long,
    val importance: Int,
)
