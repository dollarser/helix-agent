from pathlib import Path
p=Path('app/src/androidTest/kotlin/com/helix/app/chat/BackgroundTaskStorageDeviceTest.kt');s=p.read_text();idx=s.index('    private fun request(');s=s[:idx]+'''    @Test fun taskAndGoalSnapshotsStayConsistentDuringGoalDeletion() =
        fixture { storage, coordinator, _ ->
            val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
            val start = java.util.concurrent.CountDownLatch(1)
            val reader = Thread {
                try {
                    start.await()
                    repeat(100) {
                        BackgroundTaskQuery(storage).read()
                        GoalSummaryQuery(storage).forSession("s")
                    }
                } catch (error: Throwable) {
                    errors.add(error)
                }
            }
            reader.start()
            start.countDown()
            repeat(30) { index ->
                val goal = coordinator.create("Goal $index", emptyList(), GoalBudgets(5, 5, 10000, 60000, 10000, 0))
                val turn = requireNotNull(coordinator.start(request(goal, "race-$index")))
                turn.coordinator.terminalize(ModelStreamTerminal(TurnState.CANCELLED, null))
                storage.withTransaction { storage.goals.delete(goal) }
            }
            reader.join(10000)
            assertFalse("Snapshot reader did not finish", reader.isAlive)
            assertTrue(errors.joinToString(), errors.isEmpty())
        }

'''+s[idx:];p.write_text(s)
