package com.helix.app.proot

import com.helix.app.goal.GoalEvidenceFailure
import com.helix.app.goal.GoalEvidenceRejected
import com.helix.core.storage.HelixStorage
import java.io.File

/** Consumer has no PRoot artifact source. */
@Suppress("UNUSED_PARAMETER")
internal class ProotGoalArtifacts(
    storage: HelixStorage,
    workspace: File,
) {
    fun paths(
        turnId: String,
        callId: String,
    ): List<String> = emptyList()

    fun read(
        turnId: String,
        callId: String,
        path: String,
    ): ByteArray = throw GoalEvidenceRejected(GoalEvidenceFailure.UNSUPPORTED_SOURCE)
}
