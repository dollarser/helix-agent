package com.helix.app.proot

import com.helix.app.goal.GoalEvidenceFailure
import com.helix.app.goal.GoalEvidenceRejected
import com.helix.core.storage.HelixStorage
import java.io.File

/** Local persisted results only. This facade has no Runtime client or binding operation. */
internal class ProotGoalArtifacts(
    storage: HelixStorage,
    workspace: File,
) {
    private val scratch = File(workspace, ".helix/goal-evidence-staging")
    private val results = ProotResultStore(storage, workspace, scratch)

    fun paths(
        turnId: String,
        callId: String,
    ): List<String> =
        guarded {
            val archive = results.readLocal(turnId, callId) ?: return@guarded emptyList()
            ProotResultPreview.read(archive, scratch).files.map { it.path }
        }

    fun read(
        turnId: String,
        callId: String,
        path: String,
    ): ByteArray =
        guarded {
            val archive = requireNotNull(results.readLocal(turnId, callId))
            ProotEvidenceContent.read(archive, scratch, path)
        }

    private fun <T> guarded(block: () -> T): T =
        try {
            com.helix.app.goal
                .checkGoalEvidenceReadActive()
            block().also {
                com.helix.app.goal
                    .checkGoalEvidenceReadActive()
            }
        } catch (failure: com.helix.runtime.proot.core.JobArchiveException) {
            throw GoalEvidenceRejected(GoalEvidenceFailure.CONTENT_CHANGED, failure)
        } catch (failure: IllegalStateException) {
            throw GoalEvidenceRejected(GoalEvidenceFailure.CONTENT_CHANGED, failure)
        }
}
