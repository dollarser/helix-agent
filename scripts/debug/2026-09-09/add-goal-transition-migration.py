from pathlib import Path
p=Path('core/storage/src/androidTest/kotlin/com/helix/core/storage/RoomMigrationFixtureTest.kt');s=p.read_text();marker='    @Test\n    fun v1ExportExistsAsATestAsset()';s=s.replace(marker,'''    @Test
    fun v11ToV12ReleasesOnlyRetiredEvidenceBlockerWithoutStartingRuns() {
        val name = "model-goal-migration"
        context.deleteDatabase(name)
        helper.createDatabase(name, 11).use { db ->
            for ((id, reason) in listOf("binding" to "EVIDENCE_BINDING_REQUIRED", "budget" to "remainingBudget")) {
                db.execSQL(
                    "INSERT INTO goals(id,objective,criteria,budgets,state,correlationId,runCount,modelCalls," +
                        "toolCalls,totalTokens,runTimeMillis,currentWakeMillis,retries) " +
                        "VALUES (?, 'fixture','[]','{}','BLOCKED','c',1,1,0,10,0,0,0)", arrayOf(id),
                )
                val outcome = if (id == "binding") "BLOCKED($reason)" else "BUDGET_EXHAUSTED($reason)"
                db.execSQL(
                    "INSERT INTO goal_runs(id,goalId,wakeReason,outcome,startedAt,endedAt,modelCalls,toolCalls,tokens) " +
                        "VALUES (?,?,'USER_OPEN',?,1,2,1,0,10)", arrayOf("r-$id", id, outcome),
                )
            }
        }
        helper.runMigrationsAndValidate(name, 12, true, HelixDatabase.MIGRATION_11_12).use { db ->
            db.query("SELECT id,state,totalTokens FROM goals ORDER BY id").use {
                assertTrue(it.moveToFirst())
                assertEquals("binding", it.getString(0))
                assertEquals("PAUSED", it.getString(1))
                assertEquals(10L, it.getLong(2))
                assertTrue(it.moveToNext())
                assertEquals("BLOCKED", it.getString(1))
            }
            db.query("SELECT COUNT(*) FROM goal_runs WHERE endedAt IS NOT NULL").use {
                assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0))
            }
        }
        context.deleteDatabase(name)
    }

'''+marker);p.write_text(s)
