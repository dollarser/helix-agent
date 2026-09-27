package com.helix.app.files

import com.helix.tools.framework.ExecutionOwnership
import java.util.UUID

/** Shares the Runtime/tool owner; cancellation requests never count as resource release. */
internal class WorkspaceCleanupAdmission(
    private val ownership: ExecutionOwnership,
) {
    fun run(cleanup: () -> Unit) {
        val callId = "workspace-cleanup-${UUID.randomUUID()}"
        checkNotNull(ownership.acquire(callId)) { "Execution is busy; workspace retained" }.use { cleanup() }
    }
}
