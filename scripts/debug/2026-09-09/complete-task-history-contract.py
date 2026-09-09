from pathlib import Path
p=Path('core/storage/src/main/kotlin/com/helix/core/storage/dao/ConversationDaos.kt')
s=p.read_text().replace('interface TurnDao {','''interface TurnDao {
    @Query(
        "SELECT * FROM turns WHERE resultCollectedAt IS NULL " +
            "OR state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED') " +
            "ORDER BY startedAt DESC, rowid DESC",
    )
    fun pendingTasks(): List<TurnEntity>
''');p.write_text(s)
p=Path('core/storage/src/main/kotlin/com/helix/core/storage/repository/ConversationRepositories.kt')
s=p.read_text().replace('    fun recent(limit: Int = 200): List<TurnEntity> {','    fun pendingTasks(): List<TurnEntity> = dao.pendingTasks()\n\n    fun recent(limit: Int = 200): List<TurnEntity> {');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/chat/BackgroundTasks.kt');s=p.read_text().replace('storage.turns.listActive() + storage.turns.recent()', 'storage.turns.pendingTasks() + storage.turns.recent()');p.write_text(s)
p=Path('app/src/androidTest/kotlin/com/helix/app/recovery/ProcessRecoveryTest.kt');s=p.read_text()
for name in ['budgetBoundaryParksAtomicallyWithTheUsageAudit','exhaustedRunSurvivesReopenWithoutRecoveryRewritingItsOutcome']:
 a=s.index('    fun '+name);b=s.find('\n    @Test',a)
 s=s[:a]+s[a:b].replace('GoalState.PAUSED.name','GoalState.BLOCKED.name')+s[b:]
p.write_text(s)
p=Path('app/src/androidTest/kotlin/com/helix/app/chat/BackgroundTaskStorageDeviceTest.kt');s=p.read_text().replace('    private fun request(','''    @Test fun uncollectedResultsRemainVisibleBeyondRecentHistoryLimit() =
        fixture { storage, _, _ ->
            repeat(205) { index ->
                val turn = TurnCoordinator.start(
                    storage, clock, ::id,
                    TurnStartSpec("s", "t-$index", "m-$index", "snapshot", "input"),
                )
                turn.terminalize(ModelStreamTerminal(TurnState.CANCELLED, null))
            }
            assertEquals(205, BackgroundTaskQuery(storage).read().size)
            assertTrue(BackgroundTaskQuery(storage).read().any { it.id == "t-0" && it.canCollect })
        }

    private fun request(''');p.write_text(s)
