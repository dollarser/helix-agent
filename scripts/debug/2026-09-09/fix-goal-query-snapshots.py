from pathlib import Path
p=Path('app/src/main/kotlin/com/helix/app/chat/GoalSummaryUi.kt');s=p.read_text().replace('runs.maxByOrNull { it.startedAt }','runs.lastOrNull()');s=s.replace('    fun forSession(sessionId: String): List<GoalSummaryUi> {','''    fun forSession(sessionId: String): List<GoalSummaryUi> {
        var snapshot = emptyList<GoalSummaryUi>()
        storage.withTransaction { snapshot = readSnapshot(sessionId) }
        return snapshot
    }

    private fun readSnapshot(sessionId: String): List<GoalSummaryUi> {''');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/chat/BackgroundTasks.kt');s=p.read_text().replace('    fun read(): List<BackgroundTaskUi> =','''    fun read(): List<BackgroundTaskUi> {
        var snapshot = emptyList<BackgroundTaskUi>()
        storage.withTransaction { snapshot = readSnapshot() }
        return snapshot
    }

    private fun readSnapshot(): List<BackgroundTaskUi> =''');p.write_text(s)
p=Path('app/src/androidTest/kotlin/com/helix/app/chat/GoalRunCoordinatorDeviceTest.kt');s=p.read_text().replace('invalidTurnRollsBackRunAndCreationRejectsMissingCriteria','invalidTurnRollsBackRunAndObjectiveOnlyCreationSucceeds').replace('            assertThrows(IllegalArgumentException::class.java) { coordinator.create("Empty", emptyList(), budgets) }','''            val objectiveOnly = coordinator.create("Explain clearly", emptyList(), budgets)
            assertTrue(storage.goals.resolve(objectiveOnly).criteria.isEmpty())''');p.write_text(s)
