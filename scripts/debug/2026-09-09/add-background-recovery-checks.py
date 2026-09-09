from pathlib import Path
p=Path('app/src/androidTest/kotlin/com/helix/app/chat/BackgroundTaskStorageDeviceTest.kt')
s=p.read_text(); marker='    private fun request('
s=s.replace(marker, '''    @Test fun processRecoveryKeepsUnknownEffectsBlockedEvenAfterPauseRequest() =
        fixture { storage, coordinator, goal ->
            requireNotNull(coordinator.start(request(goal, "first")))
            storage.toolCalls.append("unknown", "first", "unknown", "files.write", "1", "{}", "NEEDS_REVIEW")
            assertTrue(storage.turns.requestPause("first", 2001))
            RecoveryCoordinatorApp(storage, clock).recover()
            assertEquals("BLOCKED", storage.goals.resolve(goal).state)
            assertFalse(GoalBlockerResolution(storage, clock, ::id).resolve(goal, "s", true))
            assertNull(coordinator.start(request(goal, "second")))
            assertEquals("NEEDS_REVIEW", storage.toolCalls.resolve("unknown").state)
        }

    @Test fun recoveryChargesReservedBudgetAndKeepsExhaustionBlocked() =
        fixture { storage, coordinator, goal ->
            val first = requireNotNull(coordinator.start(request(goal, "first")))
            val journal = com.helix.app.recovery.GoalUsageReservations(storage)
            assertTrue(
                journal.reserve(
                    com.helix.app.recovery.GoalUsageReservations.Request(
                        "reservation", first.runId,
                        com.helix.app.recovery.GoalUsageReservations.Kind.MODEL, 10000, 0,
                    ),
                ),
            )
            RecoveryCoordinatorApp(storage, clock).recover()
            assertEquals("BLOCKED", storage.goals.resolve(goal).state)
            assertEquals(10000L, storage.goals.resolve(goal).totalTokens)
            assertFalse(GoalBlockerResolution(storage, clock, ::id).resolve(goal, "s", true))
            assertNull(coordinator.start(request(goal, "second")))
            RecoveryCoordinatorApp(storage, clock).recover()
            assertEquals(10000L, storage.goals.resolve(goal).totalTokens)
        }

'''+marker)
p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/foreground/DataSyncForegroundController.kt')
s=p.read_text().replace('Driven from the chat-screen collector (main dispatcher)', 'Driven from the aggregate task collector (main dispatcher)');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/recovery/RecoveryCoordinatorApp.kt')
s=p.read_text().replace('Parks a RUNNING goal in PAUSED and closes its open runs (ADR-0004: the checkpoint is kept', 'Parks a RUNNING goal in PAUSED, or BLOCKED for unresolved effects/budget, and closes runs.\n     * ADR-0004/0039: the checkpoint is kept')
p.write_text(s)
