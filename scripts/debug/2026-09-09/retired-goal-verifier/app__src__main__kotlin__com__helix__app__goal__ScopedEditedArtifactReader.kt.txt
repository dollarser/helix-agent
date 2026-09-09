package com.helix.app.goal

import com.helix.core.agent.ArtifactCriterionCheck
import com.helix.core.workspace.ScopeNotAvailable
import com.helix.core.workspace.SymlinkEscapesRoot
import com.helix.core.workspace.SymlinkInPath
import com.helix.core.workspace.WorkspaceArtifactStore
import java.util.Base64

/** Uses the existing live scope resolver and containment checks; it never creates a grant. */
internal class ScopedEditedArtifactReader(
    private val workspace: WorkspaceArtifactStore,
) {
    fun read(expected: EditedArtifactContent): ByteArray {
        val window =
            try {
                workspace.readWindow(expected.path, 0, ArtifactCriterionCheck.MAX_CONTENT_BYTES.toLong())
            } catch (failure: ScopeNotAvailable) {
                unavailable(failure)
            } catch (failure: SymlinkEscapesRoot) {
                unavailable(failure)
            } catch (failure: SymlinkInPath) {
                unavailable(failure)
            }
        require(window.eof && window.sizeBytes == expected.size) { "edited file changed or exceeds evidence limit" }
        val bytes =
            window.text?.toByteArray(Charsets.UTF_8) ?: Base64.getDecoder().decode(requireNotNull(window.base64))
        return expected.verify(bytes)
    }

    private fun unavailable(failure: RuntimeException): Nothing =
        throw GoalEvidenceRejected(GoalEvidenceFailure.READ_UNAVAILABLE, failure)
}
