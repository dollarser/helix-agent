package com.helix.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.helix.core.storage.entity.HighSensitivityRuleEntity

/**
 * ADR-0005 stored high-sensitivity egress rules (HXA-068). Rows are created only through the
 * repository's explicit [com.helix.core.storage.repository.HighSensitivityRuleRepository.save] and
 * removed only through its explicit [com.helix.core.storage.repository.HighSensitivityRuleRepository.revoke];
 * the Policy Engine reads them through [list] and never mutates them (no sliding renewal — a
 * re-approval is a brand-new rule, ADR-0005).
 */
@Dao
interface HighSensitivityRuleDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(rule: HighSensitivityRuleEntity)

    /** Revoked through the repository's explicit revoke; returns the affected-row count. */
    @Query("DELETE FROM high_sensitivity_rules WHERE id = :id")
    fun delete(id: String): Int

    @Query("SELECT * FROM high_sensitivity_rules WHERE id = :id")
    fun byId(id: String): HighSensitivityRuleEntity?

    /**
     * Every stored rule, deterministic (rowid) so the Policy Engine sees a stable set; [list] is
     * the bounded read the live rule-provider uses (roadmap HXA-068 — the rules are few by design).
     */
    @Query("SELECT * FROM high_sensitivity_rules ORDER BY createdAtEpoch DESC, rowid DESC")
    fun list(): List<HighSensitivityRuleEntity>
}
