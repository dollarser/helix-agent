package com.helix.app.proot

import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.storage.HelixStorage
import com.helix.runtime.proot.ipc.DetachedJobBinding
import com.helix.runtime.proot.ipc.ProotJobSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Write-ahead identity only; never authorization or proof that submission succeeded.
 *
 * HXA-209 C5 (ADR section 4 + section 5): the prepared-job row BINDS the background work to
 * its session and to the authorization configuration that covered its approval — the
 * session id, the mode and the rule-set [SessionPermissionConfig.configVersion]. The row is
 * written just before `ProotJobClient.submit`, so it exists for exactly the jobs that are
 * or become real; the recheck refusal happens BEFORE it and writes nothing.
 */
internal class ProotJobBindingStore(
    private val storage: HelixStorage,
    /**
     * The live session-config read for the v2 binding row. The default (no config) is for
     * the RECOVERY PATHS, which only call [resolve] and never [record]; the single writer
     * (the tool module) passes the live seam so every prepared-job row carries the
     * authorization version.
     */
    private val configFor: (sessionId: String) -> SessionPermissionConfig? = { null },
    private val bootCount: () -> Int? = { null },
) {
    fun record(
        call: LinuxRunTool.ParsedLinuxCall,
        spec: ProotJobSpec,
    ) = recordPrepared(call, spec, detached = false)

    fun recordDetached(
        call: LinuxRunTool.ParsedLinuxCall,
        spec: ProotJobSpec,
    ) = recordPrepared(call, spec, detached = true)

    private fun recordPrepared(
        call: LinuxRunTool.ParsedLinuxCall,
        spec: ProotJobSpec,
        detached: Boolean,
    ) {
        val turn = storage.turns.resolve(requireNotNull(call.turnId))
        check(call.sessionId == turn.sessionId) { "job session does not match its turn" }
        val stored = requireNotNull(storage.toolCalls.byTurnAndCallId(turn.id, call.toolCallId))
        check(stored.state == "RUNNING")
        val payload =
            jobPreparedPayload(stored.callId, turn.id, turn.sessionId, spec, configFor(turn.sessionId), detached)
                .let { prepared ->
                    if (!detached) {
                        prepared
                    } else {
                        JsonObject(
                            prepared + buildJsonObject { put("bootCount", bootCount()) },
                        )
                    }
                }
        storage.auditEvents.append(
            id = eventId(call.toolCallId),
            correlationId = turn.sessionId,
            type = "proot.job_prepared",
            actor = "platform",
            redactedPayload = payload.toString(),
            timestamp = System.currentTimeMillis(),
        )
    }

    fun resolve(toolCallId: String): JsonObject {
        val event = storage.auditEvents.resolve(eventId(toolCallId))
        check(event.type == "proot.job_prepared")
        val payload = Json.parseToJsonElement(event.redactedPayload) as JsonObject
        requireVersion(payload)
        check(payload.getValue("toolCallId").jsonPrimitive.content == toolCallId)
        return payload
    }

    /** Resolve only by an original call ID and the trusted current session, never model-supplied job fields. */
    fun resolveDetached(
        sessionId: String,
        toolCallId: String,
    ): DetachedJobBinding {
        val binding = detachedBinding(resolve(toolCallId), sessionId, toolCallId)
        val turn = storage.turns.resolve(binding.turnId)
        check(turn.sessionId == sessionId) { "job turn belongs to another session" }
        check(storage.toolCalls.byTurnAndCallId(turn.id, toolCallId) != null) { "original job call missing" }
        return binding
    }

    /**
     * Accepts every payload version this build has written. Version 1 (before the
     * session binding existed) stays resolvable after an app update — the audit rows are
     * durable and outlive the build that wrote them.
     */
    private fun requireVersion(payload: JsonObject) {
        val version = payload.getValue("version").jsonPrimitive.content
        check(version in ACCEPTED_VERSIONS) { "unsupported proot job payload version: $version" }
    }

    private fun eventId(toolCallId: String): String = "proot-job-$toolCallId"

    companion object {
        const val PAYLOAD_VERSION = 2

        private val ACCEPTED_VERSIONS = setOf("1", PAYLOAD_VERSION.toString(), "3")

        internal fun detachedBinding(
            payload: JsonObject,
            sessionId: String,
            toolCallId: String,
        ): DetachedJobBinding {
            check(payload.getValue("version").jsonPrimitive.content == "3") { "not a detached job binding" }
            check(payload.getValue("executionMode").jsonPrimitive.content == "DETACHED")
            check(payload.getValue("sessionId").jsonPrimitive.content == sessionId) { "job belongs to another session" }
            check(payload.getValue("toolCallId").jsonPrimitive.content == toolCallId) { "original call does not match" }
            return DetachedJobBinding(
                sessionId,
                payload.getValue("turnId").jsonPrimitive.content,
                toolCallId,
                payload.getValue("jobId").jsonPrimitive.content,
                payload.getValue("executionId").jsonPrimitive.content,
                payload.getValue("inputManifestSha256").jsonPrimitive.content,
            )
        }

        /**
         * The redacted payload of one prepared background job (version [PAYLOAD_VERSION]):
         * the job identity, the session/task it belongs to (ADR section 4: background work
         * must belong to the session + task) and the authorization configuration version
         * that covered its approval (section 5). Primitives only — no command, environment
         * or path. Stateless on purpose: host-testable without a storage instance.
         */
        internal fun jobPreparedPayload(
            toolCallId: String,
            turnId: String,
            sessionId: String,
            spec: ProotJobSpec,
            config: SessionPermissionConfig?,
            detached: Boolean = false,
        ): JsonObject =
            buildJsonObject {
                put("version", if (detached) 3 else PAYLOAD_VERSION)
                if (detached) put("executionMode", "DETACHED")
                put("toolCallId", toolCallId)
                put("turnId", turnId)
                put("sessionId", sessionId)
                // Absent session config (unwired seam / missing row): present-but-null keys,
                // the shape stays stable.
                put("mode", config?.mode?.name)
                put("configVersion", config?.configVersion)
                put("jobId", spec.jobId)
                put("executionId", spec.executionId)
                put("inputManifestSha256", spec.inputManifestSha256)
            }
    }
}
