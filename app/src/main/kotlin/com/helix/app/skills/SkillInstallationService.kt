package com.helix.app.skills

import com.helix.extensions.skills.SkillEnablementScope
import com.helix.extensions.skills.SkillImportService
import com.helix.extensions.skills.SkillKey
import com.helix.extensions.skills.SkillRepository
import java.nio.file.Path

/** Commits only the freshly captured content matching the user's reviewed hash. */
class SkillInstallationService(
    private val authoring: SkillAuthoringService,
    private val importer: SkillImportService,
    private val repository: SkillRepository,
    private val snapshots: Path,
) {
    @Synchronized
    fun install(
        path: String,
        expectedHash: String,
        cancelled: () -> Boolean = { false },
    ): SkillKey {
        require(Regex("[a-f0-9]{64}").matches(expectedHash)) { "SKILL_INVALID_HASH" }
        return authoring.withStaged(path, cancelled) { staged ->
            require(staged.preview.snapshotHash == expectedHash) { "SKILL_CONTENT_CHANGED: preview again" }
            check(!cancelled()) { "IMPORT_CANCELLED" }
            // After this boundary return the committed identity even if cancellation arrives.
            // A lost response is reconciled by the same hash in the repository, never a new version.
            repository.registerSnapshot(importer.commit(staged, snapshots))
        }
    }

    fun isEnabled(key: SkillKey): Boolean = repository.list().single { it.key == key }.enabled

    fun enable(key: SkillKey) = repository.setEnabled(key, true, SkillEnablementScope.GLOBAL)
}
