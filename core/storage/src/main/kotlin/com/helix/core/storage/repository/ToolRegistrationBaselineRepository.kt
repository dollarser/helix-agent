package com.helix.core.storage.repository

import com.helix.core.storage.dao.ToolBaselineMetaDao
import com.helix.core.storage.dao.ToolRegistrationBaselineDao
import com.helix.core.storage.entity.ToolBaselineMetaEntity
import com.helix.core.storage.entity.ToolRegistrationBaselineEntity

/**
 * The trusted tool identity the baseline keys on: the origin's canonical form + the stable tool
 * name — exactly the key a [com.helix.core.storage.entity.ToolApprovalPreferenceEntity] uses, so a
 * baseline marker and a user preference for one tool always line up.
 */
data class ToolBaselineIdentity(
    val sourceRef: String,
    val toolName: String,
)

/**
 * The trusted tool-registration/upgrade baseline (HXA-200, ADR-0052 point 1). This is the ONLY
 * write path for the baseline: the app's trusted registration (built-in tools at startup, and
 * app upgrades) calls [reconcile]. The model, Skill, MCP, A2A and the UI never write it — "is this
 * tool new?" is decided from this trusted record, never from an empty preference row or a model
 * claim (ADR-0052 point 1; 2026-09-15 mechanism addendum).
 *
 * [reconcile] is first-write-wins and idempotent: it records the founding anchor only on the very
 * first run (a fresh install, so every bundled tool is OLD), and stamps a [firstSeenVersionCode] on
 * each tool the first time the trusted path sees it (a tool first seen in a later versionCode is the
 * "new" one for that build). The "new" DECISION itself is the pure
 * [com.helix.core.policy.ToolBaseline.isNewDefault]; this repository only persists the markers.
 */
class ToolRegistrationBaselineRepository(
    private val baselineDao: ToolRegistrationBaselineDao,
    private val metaDao: ToolBaselineMetaDao,
) {
    /**
     * The app versionCode this device's baseline was first established at, or null before the
     * trusted path has ever run (a fresh database). Null means "no baseline yet": there is no
     * trusted basis to call any tool new, so everything resolves UNSET.
     */
    fun foundingVersionCode(): Long? =
        metaDao
            .byId(ToolBaselineMetaEntity.BASELINE_ROW_ID)
            ?.foundingVersionCode

    /** The versionCode a tool was first trustedly seen at, or null when it has no marker. */
    fun firstSeenVersionCode(
        sourceRef: String,
        toolName: String,
    ): Long? = baselineDao.firstSeenVersionCode(sourceRef, toolName)

    /**
     * Records the trusted registration of [identities] under [currentVersionCode]. Idempotent and
     * first-write-wins: the founding anchor is set once (the current versionCode on the first run);
     * each identity marker is stamped once (its first-seen versionCode), never re-stamped. Returns
     * the count of identity markers that exist afterwards (the whole input, since every input
     * identity ends with a marker) — callers may ignore it.
     */
    fun reconcile(
        currentVersionCode: Long,
        identities: List<ToolBaselineIdentity>,
        nowEpoch: Long,
    ): Int {
        metaDao.insertIgnore(
            ToolBaselineMetaEntity(
                id = ToolBaselineMetaEntity.BASELINE_ROW_ID,
                foundingVersionCode = currentVersionCode,
                updatedAtEpoch = nowEpoch,
            ),
        )
        identities.forEach { identity ->
            baselineDao.insertIgnore(
                ToolRegistrationBaselineEntity(
                    sourceRef = identity.sourceRef,
                    toolName = identity.toolName,
                    firstSeenVersionCode = currentVersionCode,
                    updatedAtEpoch = nowEpoch,
                ),
            )
        }
        return identities.size
    }
}
