package com.helix.app.tool

import com.helix.core.model.OperationEffect
import com.helix.core.model.ToolOperationClass
import com.helix.core.policy.OperationFootprint
import com.helix.core.policy.RmCommandRule
import com.helix.core.workspace.FileScopePath
import com.helix.tools.framework.CallEffectClassification
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolDispatchRequest
import com.helix.tools.framework.ToolEffectClassifier
import com.helix.tools.framework.ToolOrigin
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The app's [ToolEffectClassifier] (HXA-209 B3, ADR-PERMISSIONS-001 section 2 step 4,
 * execution-domain matrix section 5): the platform-side effect classification of every
 * execution lane, feeding the single session permission resolver.
 *
 * Rules (fail-closed: an unproven effect is UNDETERMINED, never dropped; a parseable effect is
 * never guessed optimistic):
 * - Shell (`code.linux.run`, developer only): COMMAND_EXECUTION is determined; the six other
 *   categories are UNDETERMINED — a `cd` is not a containment boundary and no isolation layer
 *   proves the absence of a file/network/device effect. The explicit `rm -rf` hit is the
 *   separate floor rule (ADR section 3).
 * - QuickJS (`code.javascript.run`): the sandboxed engine is the whole effect surface —
 *   COMMAND_EXECUTION, nothing else (its host bridge is a closed, platform-owned set; doc 03).
 * - MCP / A2A origins: UNDETERMINED remote business mutation — a `readOnlyHint`, a tool name
 *   and the model-visible description never prove the absence of a remote write (ADR 1.2);
 *   the HTTP method is never a classification input.
 * - File lane: each `scope:<scopeId>:<relative>` argument is resolved against the session's
 *   bound workspace scope id — inside is _WORKSPACE, anything else (and every unparseable
 *   reference) is _EXTERNAL. A move mutates its source; copy/extract read theirs.
 * - Everything else READ_ONLY: no gated effect (local reads and network reads are never gated —
 *   READ_ONLY still allows network queries and browsing).
 * - Browser interaction (click/type/scroll/download): UNDETERMINED remote business mutation.
 * - Everything else the app can mutate (device/external actions, privileged, unknown):
 *   UNDETERMINED device system mutation — the conservative bucket that ASKs in every preset
 *   that asks for device operations.
 */
class SessionToolEffectClassifier(
    /** The session's bound workspace scope id (`session.directoryRef ?: APP_SCOPE_ID`), or null. */
    private val sessionWorkspace: (sessionId: String) -> String?,
) : ToolEffectClassifier {
    private fun isBuiltInMetadata(descriptor: ToolDescriptor): Boolean =
        descriptor.origin == ToolOrigin.BuiltInOrigin && descriptor.operationClass == ToolOperationClass.METADATA

    override fun classify(
        request: ToolDispatchRequest,
        descriptor: ToolDescriptor,
    ): CallEffectClassification {
        val name = descriptor.name.value
        return when (name) {
            LINUX_RUN, LINUX_JOB_START -> {
                linuxClassification(request.args)
            }

            LINUX_JOB_CANCEL, QUICKJS_RUN -> {
                CallEffectClassification(
                    OperationFootprint(effects = setOf(OperationEffect.COMMAND_EXECUTION)),
                )
            }

            else -> {
                when {
                    descriptor.origin is ToolOrigin.McpOrigin || descriptor.origin is ToolOrigin.A2aOrigin -> {
                        undetermined(OperationEffect.REMOTE_BUSINESS_MUTATION)
                    }

                    name in FILE_READ_TOOLS -> {
                        fileFootprint(name, request.args, request.sessionId, readTool = true)
                    }

                    name in FILE_MUTATION_TOOLS -> {
                        fileFootprint(name, request.args, request.sessionId, readTool = false)
                    }

                    isBuiltInMetadata(descriptor) -> {
                        // Closed session metadata is admitted by Policy, not device mutation.
                        // The registry forbids external tools from claiming METADATA.
                        CallEffectClassification(OperationFootprint())
                    }

                    descriptor.operationClass == ToolOperationClass.READ_ONLY -> {
                        CallEffectClassification(OperationFootprint())
                    }

                    name.startsWith(BROWSER_PREFIX) -> {
                        undetermined(OperationEffect.REMOTE_BUSINESS_MUTATION)
                    }

                    name == HTTP_FETCH -> {
                        CallEffectClassification(OperationFootprint())
                    }

                    descriptor.operationClass == ToolOperationClass.NETWORK -> {
                        undetermined(OperationEffect.REMOTE_BUSINESS_MUTATION)
                    }

                    else -> {
                        undetermined(OperationEffect.DEVICE_SYSTEM_MUTATION)
                    }
                }
            }
        }
    }

    private fun undetermined(effect: OperationEffect) =
        CallEffectClassification(OperationFootprint(undeterminedEffects = setOf(effect)))

    /** The Shell lane: a command runs with the app's full reach; only the rm floor is determined. */
    private fun linuxClassification(args: JsonObject): CallEffectClassification {
        val argv =
            (args["argv"] as? JsonArray)?.mapNotNull { element ->
                (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            }
        val script = (args["script"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val rmCommandHit =
            when {
                argv != null -> RmCommandRule.checkArgv(argv) is RmCommandRule.Verdict.RmRfDir
                script != null -> RmCommandRule.checkScript(script) is RmCommandRule.Verdict.RmRfDir
                else -> false
            }
        return CallEffectClassification(
            OperationFootprint(
                effects = setOf(OperationEffect.COMMAND_EXECUTION),
                undeterminedEffects =
                    setOf(
                        OperationEffect.FILE_READ_WORKSPACE,
                        OperationEffect.FILE_READ_EXTERNAL,
                        OperationEffect.FILE_MUTATION_WORKSPACE,
                        OperationEffect.FILE_MUTATION_EXTERNAL,
                        OperationEffect.REMOTE_BUSINESS_MUTATION,
                        OperationEffect.DEVICE_SYSTEM_MUTATION,
                    ),
            ),
            rmCommandHit = rmCommandHit,
        )
    }

    /**
     * The file lane: every `scope:<scopeId>:<...>` argument maps to a read and/or a mutation
     * effect, WORKSPACE when the scope id is the session's bound workspace, EXTERNAL otherwise
     * (including every unparseable reference — fail closed).
     */
    private fun fileFootprint(
        name: String,
        args: JsonObject,
        sessionId: String,
        readTool: Boolean,
    ): CallEffectClassification {
        val workspaceId = sessionWorkspace(sessionId)
        val effects = mutableSetOf<OperationEffect>()
        for ((key, element) in args) {
            val reference = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (reference == null || !reference.startsWith(SCOPE_PREFIX)) continue
            val scopeId = runCatching { FileScopePath.fromModelReference(reference).scopeId }.getOrNull()
            val inWorkspace = workspaceId != null && scopeId != null && scopeId == workspaceId
            val read =
                if (inWorkspace) OperationEffect.FILE_READ_WORKSPACE else OperationEffect.FILE_READ_EXTERNAL
            val mutation =
                if (inWorkspace) OperationEffect.FILE_MUTATION_WORKSPACE else OperationEffect.FILE_MUTATION_EXTERNAL
            when {
                readTool -> {
                    effects += read
                }

                key in SOURCE_KEYS -> {
                    effects += read
                    // A move also MUTATES its source (a copy whose source is deleted).
                    if (name == FILES_MOVE) effects += mutation
                }

                key in TARGET_KEYS -> {
                    effects += mutation
                }

                // An unrecognized scope-referencing argument on a mutation tool is a mutation:
                // a file tool that gains a path argument is never silently under-classified.
                else -> {
                    effects += mutation
                }
            }
        }
        return CallEffectClassification(OperationFootprint(effects = effects))
    }

    companion object {
        const val LINUX_RUN: String = "code.linux.run"
        const val LINUX_JOB_START: String = "code.linux.job.start"
        const val LINUX_JOB_CANCEL: String = "code.linux.job.cancel"

        const val QUICKJS_RUN: String = "code.javascript.run"

        const val HTTP_FETCH: String = "http.fetch"

        private const val BROWSER_PREFIX = "browser."

        private const val SCOPE_PREFIX = "scope:"

        private const val FILES_MOVE: String = "files.move"

        private val FILE_READ_TOOLS: Set<String> =
            setOf("read", "files.list", "files.stat", "files.search")

        private val FILE_MUTATION_TOOLS: Set<String> =
            setOf(
                "write",
                "edit",
                "files.copy",
                "files.move",
                "files.delete",
                "files.mkdir",
                "files.extract",
                "files.archive",
            )

        private val SOURCE_KEYS: Set<String> = setOf("source")

        private val TARGET_KEYS: Set<String> = setOf("path", "destination")
    }
}
