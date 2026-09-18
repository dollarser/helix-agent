package com.helix.app.proot

import com.helix.app.approval.SessionPermissionService
import com.helix.core.model.OperationEffect
import com.helix.core.model.ToolAvailabilityState
import com.helix.core.policy.OperationFootprint
import com.helix.core.policy.SessionPermissionResolution
import com.helix.core.policy.SessionPermissionResolver
import com.helix.core.policy.effectiveAvailability
import com.helix.core.storage.HelixStorage
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.runtime.proot.core.ZipJobExtractor
import com.helix.runtime.proot.ipc.DetachedJobBinding
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files

/** Imports only the original approved output target, under its current scope and permission state. */
internal class DetachedJobOutput(
    private val storage: HelixStorage,
    private val workspace: WorkspaceArtifactStore,
    private val permissions: SessionPermissionService,
    private val workspaceFor: (String) -> String?,
    private val scratch: File,
) {
    @Suppress("ReturnCount") // Failed execution, absent target and absent result require no file effect.
    fun apply(
        binding: DetachedJobBinding,
        record: ProotJobRecord,
        archive: File?,
    ) {
        check(storage.turns.resolve(binding.turnId).sessionId == binding.sessionId)
        val call = requireNotNull(storage.toolCalls.byTurnAndCallId(binding.turnId, binding.toolCallId))
        check(call.name == DetachedJobTools.START)
        if (record.state != ProotJobState.SUCCEEDED) return
        val reference =
            Json
                .parseToJsonElement(call.argsJson)
                .jsonObject["output"]
                ?.jsonPrimitive
                ?.content ?: return
        checkAuthorization(binding.sessionId, reference)
        check(scratch.mkdirs() || scratch.isDirectory)
        val directory = Files.createTempDirectory(scratch.toPath(), "detached-import-").toFile()
        try {
            val extraction = ZipJobExtractor.extract(requireNotNull(archive), directory)
            val expected = requireNotNull(record.outputManifestSha256)
            check(extraction.manifestSha256 == expected)
            val imported = LinuxOutputImport.read(extraction, expected, directory) ?: return
            // Re-read immediately before the host-side write, after bounded extraction.
            checkAuthorization(binding.sessionId, reference)
            LinuxOutputImport.write(workspace, reference, imported)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun checkAuthorization(
        sessionId: String,
        reference: String,
    ) {
        val path = FileScopePath.fromModelReference(reference)
        val descriptor = DetachedJobTools.start()
        val states =
            permissions.statesFor(
                descriptor.origin.canonicalOf(),
                descriptor.name.value,
                sessionId,
                path.scopeId,
            )
        check(
            effectiveAvailability(states.global, states.workspace, states.session) != ToolAvailabilityState.DISABLED,
        ) {
            "Original Job tool is disabled; output was not imported"
        }
        val effect =
            if (path.scopeId == workspaceFor(sessionId)) {
                OperationEffect.FILE_MUTATION_WORKSPACE
            } else {
                OperationEffect.FILE_MUTATION_EXTERNAL
            }
        // The original launch authorized this exact target. A new DENY invalidates that authority;
        // an ASK does not demand a second proof for the same deferred effect (ADR-PERMISSIONS-001).
        val resolution =
            SessionPermissionResolver.resolve(
                permissions.configFor(sessionId),
                OperationFootprint(setOf(effect)),
                rmCommandHit = false,
            )
        check(resolution !is SessionPermissionResolution.Denied) { "Current session denies original output import" }
    }
}
