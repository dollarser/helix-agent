"""HXA-177 source edits; run from the owning checkout only."""
from pathlib import Path


def edit(path, old, new):
    file = Path(path)
    text = file.read_text()
    assert old in text, (path, old)
    file.write_text(text.replace(old, new))


edit('docs/development/status.md', '本批 HXA-176 已完成。ADR-0038 取代 ADR-0037；Goal 显式继续与预算不变，无新的活动实现。',
     'HXA-177 进行中：非阻塞任务列表、持久结果回收、全局前台服务与 Goal BLOCKED/PAUSED 区分。所有者已明确授权，见 ADR-0039；验收尚未完成。')
with Path('docs/development/roadmap.md').open('a') as out:
    out.write('\n### HXA-177 后台任务与 Goal 阻塞恢复\n\n状态：in progress。允许 app、core/model、core/agent、core/storage、docs 与 scripts/debug。实现跨会话任务列表、精确暂停/取消、持久结果回收、全局服务保活；BLOCKED 不直接继续，PAUSED 显式恢复。决策见 [ADR-0039](../adr/0039-background-results-and-goal-blockers.md)。验收：core/model 与 core/agent JVM、app 双变体 JVM、spotless/detekt/lint、数据库迁移及独立模拟器任务/Goal 回归。\n')
with Path('docs/adr/0004-goal-run-wake-budget-semantics.md').open('a') as out:
    out.write('\n2026-09-09：[ADR-0039](0039-background-results-and-goal-blockers.md) 部分替代等待语义，新增 BLOCKED 和用户暂停；其余预算、证据与显式唤醒约束保留。\n')
edit('core/storage/src/main/kotlin/com/helix/core/storage/entity/ConversationEntities.kt',
     '    val errorCode: String?,\n)', '    val errorCode: String?,\n    val resultCollectedAt: Long? = null,\n    val pauseRequestedAt: Long? = null,\n)')
edit('core/storage/src/main/kotlin/com/helix/core/storage/dao/ConversationDaos.kt',
     'interface TurnDao {', '''interface TurnDao {
    @Query("SELECT * FROM turns ORDER BY startedAt DESC, rowid DESC LIMIT :limit")
    fun recent(limit: Int): List<TurnEntity>

    @Query("UPDATE turns SET resultCollectedAt = :now WHERE id = :id AND resultCollectedAt IS NULL " +
        "AND state IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')")
    fun collectResult(id: String, now: Long): Int

    @Query("UPDATE turns SET pauseRequestedAt = :now WHERE id = :id AND pauseRequestedAt IS NULL " +
        "AND state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')")
    fun requestPause(id: String, now: Long): Int
''')
edit('core/storage/src/main/kotlin/com/helix/core/storage/repository/ConversationRepositories.kt',
     '    fun listActive(): List<TurnEntity> = dao.listActive()', '''    fun listActive(): List<TurnEntity> = dao.listActive()

    fun recent(limit: Int = 200): List<TurnEntity> {
        require(limit in 1..1000)
        return dao.recent(limit)
    }

    fun collectResult(id: String, now: Long): Boolean = dao.collectResult(id, now) == 1

    fun requestPause(id: String, now: Long): Boolean = dao.requestPause(id, now) == 1''')
edit('core/storage/src/main/kotlin/com/helix/core/storage/HelixDatabase.kt', 'version = 10,', 'version = 11,')
edit('core/storage/src/main/kotlin/com/helix/core/storage/HelixDatabase.kt', '        val MIGRATION_9_10 =', '''        val MIGRATION_10_11 =
            object : Migration(10, 11) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE turns ADD COLUMN resultCollectedAt INTEGER")
                    db.execSQL("ALTER TABLE turns ADD COLUMN pauseRequestedAt INTEGER")
                }
            }

        val MIGRATION_9_10 =''')
edit('core/storage/src/main/kotlin/com/helix/core/storage/HelixStorage.kt',
     'HelixDatabase.MIGRATION_9_10,', 'HelixDatabase.MIGRATION_9_10,\n                HelixDatabase.MIGRATION_10_11,')
edit('core/model/src/main/kotlin/com/helix/core/model/GoalState.kt', '    PAUSED(false),', '    PAUSED(false),\n    BLOCKED(false),')
edit('core/model/src/main/kotlin/com/helix/core/model/GoalState.kt',
     'RUNNING -> setOf(INPUT_REQUIRED, PAUSED, COMPLETED, FAILED, CANCELLED)',
     'RUNNING -> setOf(INPUT_REQUIRED, PAUSED, BLOCKED, COMPLETED, FAILED, CANCELLED)')
edit('core/model/src/main/kotlin/com/helix/core/model/GoalState.kt',
     'PAUSED -> setOf(RUNNING, CANCELLED)', 'PAUSED -> setOf(RUNNING, BLOCKED, CANCELLED)\n                BLOCKED -> setOf(PAUSED, CANCELLED)')
edit('core/agent/src/main/kotlin/com/helix/core/agent/GoalEvent.kt',
     '    data object RunFinished : GoalEvent', '''    data object RunFinished : GoalEvent

    /** Host-confirmed external dependency; Continue cannot bypass it. */
    data object Blocked : GoalEvent

    /** Host rechecked the dependency after an explicit user repair action. */
    data object BlockerResolved : GoalEvent''')
edit('core/agent/src/main/kotlin/com/helix/core/agent/GoalReducer.kt',
     '            GoalEvent.RunFinished -> onRunFinished(state)', '''            GoalEvent.RunFinished -> onRunFinished(state)
            GoalEvent.Blocked -> if (state.state in setOf(GoalState.RUNNING, GoalState.PAUSED)) {
                step(state, state.copy(state = GoalState.BLOCKED, currentWakeMillis = 0), listOf(GoalEffect.ReminderCancelled))
            } else GoalStep.unchanged(state)
            GoalEvent.BlockerResolved -> if (state.state == GoalState.BLOCKED && state.canStartRun()) {
                step(state, state.copy(state = GoalState.PAUSED))
            } else GoalStep.unchanged(state)''')
edit('core/agent/src/main/kotlin/com/helix/core/agent/GoalReducer.kt',
     '(state.state == GoalState.PAUSED || state.state == GoalState.INPUT_REQUIRED)',
     '(state.state in setOf(GoalState.PAUSED, GoalState.INPUT_REQUIRED, GoalState.BLOCKED))')
edit('core/agent/src/main/kotlin/com/helix/core/agent/GoalReducer.kt',
     'val checkpointable = state in setOf(GoalState.RUNNING, GoalState.PAUSED, GoalState.INPUT_REQUIRED)',
     'val checkpointable = state in setOf(GoalState.RUNNING, GoalState.PAUSED, GoalState.INPUT_REQUIRED, GoalState.BLOCKED)')
edit('app/src/main/kotlin/com/helix/app/goal/GoalCriterionEditor.kt',
     'GoalState.PAUSED, GoalState.INPUT_REQUIRED)', 'GoalState.PAUSED, GoalState.INPUT_REQUIRED, GoalState.BLOCKED)')
