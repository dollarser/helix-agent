from pathlib import Path
archive=Path('scripts/debug/2026-09-09/retired-goal-verifier')
for p in [*Path('app/src').glob('*/kotlin/com/helix/app/proot/ProotGoalArtifacts.kt'),Path('core/agent/src/main/kotlin/com/helix/core/agent/ArtifactCriterionCheck.kt'),*[Path('core/agent/src/test/kotlin/com/helix/core/agent',n+'.kt') for n in ['ArtifactCriterionCheckTest','GoalCriterionBindingTest','GoalPendingReviewTest','GoalReducerCriteriaTest']]]:
 if p.exists(): (archive/(str(p).replace('/','__')+'.txt')).write_bytes(p.read_bytes());p.unlink()
p=Path('core/agent/src/main/kotlin/com/helix/core/agent/Criterion.kt');p.write_text('''package com.helix.core.agent

/** User-described success criterion. Its semantic assessment belongs to the model (ADR-0040). */
data class Criterion(val id: String, val description: String) {
    init {
        require(id.length in 1..MAX_ID_LENGTH && id.all { it in ID_CHARS })
        require(description.isNotBlank() && description.length <= MAX_DESCRIPTION_LENGTH)
    }
    companion object {
        const val MAX_ID_LENGTH = 64
        const val MAX_DESCRIPTION_LENGTH = 1024
        private val ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-".toSet()
    }
}
''')
p=Path('core/agent/src/main/kotlin/com/helix/core/agent/Goal.kt');s=p.read_text();a=s.index('    val unsatisfiedCriteria:');b=s.index('    fun remainingModelCalls',a);s=s[:a]+s[b:];s=s.replace('Only verifier-backed evidence\n * satisfies criteria; only then may the goal complete.', 'The model reports semantic completion; the host checks execution state.');p.write_text(s)
p=Path('core/agent/src/main/kotlin/com/helix/core/agent/GoalEvent.kt');s=p.read_text();a=s.index('    /**\n     * A verifier');b=s.index('    /** Sets the goal',a);s=s[:a]+s[b:];s=s.replace('All acceptance criteria carry verifier evidence and the user (or verifier flow) asks to\n     * finish. The reducer re-checks that every criterion is satisfied.', 'The coordinator consumed a model completion report after execution gates passed.\n     * The reducer validates the lifecycle, not the semantic truth of the report.');p.write_text(s)
p=Path('core/agent/src/main/kotlin/com/helix/core/agent/GoalReducer.kt');s=p.read_text();a=s.index('            is GoalEvent.CriterionSatisfied');b=s.index('\n            ',s.index('\n            }',a)+14);s=s[:a]+s[b:]
a=s.index('    private fun onCriterionSatisfied(');b=s.index('    private fun onCheckpointScheduled(',a);s=s[:a]+s[b:];p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/goal/GoalStorageMapping.kt');s=p.read_text().replace('import com.helix.core.agent.CriterionEvidence\n','').replace('import com.helix.core.storage.criteria.StoredEvidence\n','')
a=s.index('        criteria =');b=s.index('        state =',a);s=s[:a]+'        criteria = criteria.map { Criterion(it.id, it.description) },\n'+s[b:]
a=s.index('        criteria =',s.index('internal fun Goal.toStoredGoal'));b=s.index('        state =',a);s=s[:a]+'        criteria = criteria.map { StoredCriterion(it.id, it.description, null) },\n'+s[b:];p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/chat/GoalSummaryUi.kt');s=p.read_text().replace('    val satisfiedCriteria: Int,\n','').replace('                runtime.criteria.size - runtime.unsatisfiedCriteria.size,\n','');p.write_text(s)
p=Path('core/agent/src/test/kotlin/com/helix/core/agent/GoalFixtures.kt');s=p.read_text();a=s.index('    fun criterion(');b=s.index('    fun newGoal(',a);s=s[:a]+'''    fun criterion(id: String = "c1", description: String = "Login works"): Criterion = Criterion(id, description)

'''+s[b:];p.write_text(s)
p=Path('core/agent/src/test/kotlin/com/helix/core/agent/GoalReducerLifecycleTest.kt');s=p.read_text();a=s.index('        val evidence =');b=s.index('\n    }',a);s=s[:a]+'        return goal'+s[b:];s=s.replace('whose single criterion already carries verifier evidence','ready to report semantic completion');p.write_text(s)
p=Path('core/storage/src/main/kotlin/com/helix/core/storage/HelixDatabase.kt');s=p.read_text().replace('version = 11,','version = 12,');marker='        val MIGRATION_10_11 =';s=s.replace(marker,'''        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE goals SET state = 'PAUSED', finishReason = NULL WHERE state = 'BLOCKED' AND " +
                        "(SELECT outcome FROM goal_runs WHERE goalId = goals.id " +
                        "ORDER BY startedAt DESC, rowid DESC LIMIT 1) = 'BLOCKED(EVIDENCE_BINDING_REQUIRED)'",
                )
            }
        }

'''+marker);p.write_text(s)
p=Path('core/storage/src/main/kotlin/com/helix/core/storage/HelixStorage.kt');s=p.read_text().replace('                HelixDatabase.MIGRATION_10_11,','                HelixDatabase.MIGRATION_10_11,\n                HelixDatabase.MIGRATION_11_12,');p.write_text(s)
