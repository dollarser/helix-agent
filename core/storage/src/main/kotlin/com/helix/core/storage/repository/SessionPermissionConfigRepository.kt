package com.helix.core.storage.repository

import com.helix.core.model.OperationEffect
import com.helix.core.model.OperationRule
import com.helix.core.model.SessionPermissionMode
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.storage.dao.SessionPermissionConfigDao
import com.helix.core.storage.dao.SessionPermissionDefaultsDao
import com.helix.core.storage.dao.SessionPermissionDraftDao
import com.helix.core.storage.entity.SessionPermissionConfigEntity
import com.helix.core.storage.entity.SessionPermissionDefaultsEntity
import com.helix.core.storage.entity.SessionPermissionDraftEntity

/**
 * Stored session permission configurations (HXA-209, ADR-PERMISSIONS-001 section 2 step 4).
 * The single write path for the per-session [SessionPermissionConfig] rows and the app-default
 * row; the dispatcher reads through [forSession]/[appDefault] and never mutates the store.
 *
 * A missing per-session row is a lazy default: the session uses [appDefault] (a fresh install
 * compiles to READ_ONLY). No rows are seeded per session. [setForSession] upserts under the
 * session id and advances the revision; [resetToDefault] deletes the row. Presets are stored
 * as their FIXED rule table — a preset mode carrying any other table is rejected (to edit
 * rules, copy into a CUSTOM snapshot). Rehydration is fail-closed: an unknown stored mode or
 * rule table throws rather than being guessed.
 */
class SessionPermissionConfigRepository(
    private val dao: SessionPermissionConfigDao,
    private val defaults: SessionPermissionDefaultsDao,
    private val drafts: SessionPermissionDraftDao,
) {
    /** The stored config for one session, or null when the session uses the app default. */
    fun forSession(sessionId: String): SessionPermissionConfig? {
        val entity = dao.bySession(sessionId) ?: return null
        return SessionPermissionConfig(
            mode = SessionPermissionMode.valueOf(entity.mode),
            rules = SessionPermissionRulesCodec.decode(entity.rulesJson),
            configVersion = entity.configVersion,
        )
    }

    /**
     * Stores (or updates) the config for one session; returns the new revision. A preset mode
     * must carry exactly its preset rule table — the snapshot is fixed at copy time
     * (ADR section 1.2), never inherited dynamically.
     */
    fun setForSession(
        sessionId: String,
        config: SessionPermissionConfig,
        nowEpochMillis: Long,
    ): Long {
        requireValidConfig(config)
        val existing = dao.bySession(sessionId)
        val revision = (existing?.revision ?: 0L) + 1L
        dao.insert(
            SessionPermissionConfigEntity(
                sessionId = sessionId,
                mode = config.mode.name,
                rulesJson = SessionPermissionRulesCodec.encode(config.rules),
                configVersion = config.configVersion,
                revision = revision,
                createdAtEpoch = existing?.createdAtEpoch ?: nowEpochMillis,
                updatedAtEpoch = nowEpochMillis,
            ),
        )
        return revision
    }

    /** "Back to the app default" for one session: a row delete, not a stored fourth state. */
    fun resetToDefault(sessionId: String) {
        require(dao.deleteBySession(sessionId) == 1) {
            "no stored permission config for session $sessionId"
        }
    }

    /**
     * The app default config: the stored defaults row (preset mode only), or the compiled
     * READ_ONLY default when the row is missing (fresh install).
     */
    fun appDefault(): SessionPermissionConfig {
        val entity =
            defaults.byId(SessionPermissionDefaultsEntity.DEFAULTS_ROW_ID)
                ?: return SessionPermissionConfig.of(SessionPermissionMode.READ_ONLY)
        val mode = SessionPermissionMode.valueOf(entity.mode)
        require(mode != SessionPermissionMode.CUSTOM) { "the app default must not be CUSTOM" }
        return SessionPermissionConfig(
            mode = mode,
            rules = SessionPermissionConfig.presetRules(mode),
            configVersion = entity.configVersion,
        )
    }

    /**
     * Stores (or updates) the app default mode; returns the new revision. The default is a
     * PRESET only — CUSTOM requires an explicit per-session snapshot.
     */
    fun setAppDefault(
        mode: SessionPermissionMode,
        nowEpochMillis: Long,
    ): Long {
        require(mode != SessionPermissionMode.CUSTOM) { "the app default cannot be CUSTOM" }
        val existing = defaults.byId(SessionPermissionDefaultsEntity.DEFAULTS_ROW_ID)
        val revision = (existing?.revision ?: 0L) + 1L
        defaults.insert(
            SessionPermissionDefaultsEntity(
                id = SessionPermissionDefaultsEntity.DEFAULTS_ROW_ID,
                mode = mode.name,
                configVersion = SessionPermissionConfig.CURRENT_CONFIG_VERSION,
                revision = revision,
                updatedAtEpoch = nowEpochMillis,
            ),
        )
        return revision
    }

    /**
     * The stored CUSTOM draft for one session, or null when the session has none yet (the
     * settings UI reads this to populate the editor and to restore the draft when CUSTOM is
     * re-selected — ADR section 4). A missing draft is not an error: it simply means the user
     * has not copied a preset into a custom snapshot for this session.
     */
    fun customDraftFor(sessionId: String): SessionPermissionDraft? {
        val entity = drafts.bySession(sessionId) ?: return null
        return SessionPermissionDraft(
            sourcePreset = SessionPermissionMode.valueOf(entity.sourcePreset),
            rules = SessionPermissionRulesCodec.decode(entity.rulesJson),
            configVersion = entity.configVersion,
            updatedAtEpoch = entity.updatedAtEpoch,
        )
    }

    /**
     * Stores (or updates) one session's CUSTOM draft: the copied-from preset plus the
     * copied-then-edited rule snapshot (ADR section 4). This is a UI write — it does NOT touch
     * the ACTIVE config row, so a draft saved while the session is on a preset stays INERT
     * (re-select CUSTOM to apply it). The source preset must be a preset (never CUSTOM — a
     * draft is copied from a preset, not from another custom). The rule table is encoded
     * deterministically, so the stored text is the canonical snapshot.
     */
    fun setCustomDraft(
        sessionId: String,
        sourcePreset: SessionPermissionMode,
        rules: Map<OperationEffect, OperationRule>,
        nowEpochMillis: Long,
    ) {
        require(sourcePreset != SessionPermissionMode.CUSTOM) {
            "a custom draft is copied from a preset, not from CUSTOM: $sourcePreset"
        }
        val existing = drafts.bySession(sessionId)
        drafts.insert(
            SessionPermissionDraftEntity(
                sessionId = sessionId,
                sourcePreset = sourcePreset.name,
                rulesJson = SessionPermissionRulesCodec.encode(rules),
                configVersion = SessionPermissionConfig.CURRENT_CONFIG_VERSION,
                createdAtEpoch = existing?.createdAtEpoch ?: nowEpochMillis,
                updatedAtEpoch = nowEpochMillis,
            ),
        )
    }

    /** Removes one session's CUSTOM draft — a "no custom snapshot yet" state, not a stored null. */
    fun clearCustomDraft(sessionId: String) {
        drafts.deleteBySession(sessionId)
    }

    /**
     * [SessionPermissionConfig]s the UI may write: a CUSTOM snapshot (any rule table) or one of
     * the presets carrying EXACTLY its own rule table — the repository is the single place that
     * decision lives, so the UI cannot store an edited table under a preset name.
     */
    private fun requireValidConfig(config: SessionPermissionConfig) {
        if (config.mode == SessionPermissionMode.CUSTOM) {
            return
        }
        require(config.rules == SessionPermissionConfig.presetRules(config.mode)) {
            "preset ${config.mode.name} must carry its exact preset rule table; " +
                "copy it into a CUSTOM snapshot to edit individual rules"
        }
    }
}
