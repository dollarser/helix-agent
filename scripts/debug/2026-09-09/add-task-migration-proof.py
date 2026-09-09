from pathlib import Path
p=Path('core/storage/src/androidTest/kotlin/com/helix/core/storage/RoomMigrationFixtureTest.kt')
s=p.read_text();a=s.index('    fun v10ToV11KeepsTurnsAndAddsNullableTaskReceipts()');b=s.index('\n    @Test',a);part=s[a:b]
part=part.replace('            it.execSQL("INSERT INTO turns', '''            it.execSQL(
                "INSERT INTO goals(id,objective,criteria,budgets,state,correlationId,runCount,modelCalls," +
                    "toolCalls,totalTokens,runTimeMillis,currentWakeMillis,retries) " +
                    "VALUES ('g','fixture','[]','{}','PAUSED','c',1,1,0,10,0,0,0)",
            )
            it.execSQL(
                "INSERT INTO goal_runs(id,goalId,wakeReason,outcome,startedAt,endedAt,modelCalls,toolCalls,tokens) " +
                    "VALUES ('r','g','USER_OPEN','BUDGET_EXHAUSTED(maxModelCalls)',1,2,1,0,10)",
            )
            it.execSQL("INSERT INTO turns''')
part=part.replace('            db.query("SELECT state,resultCollectedAt', '''            db.query("SELECT state FROM goals WHERE id='g'").use {
                assertTrue(it.moveToFirst())
                assertEquals("BLOCKED", it.getString(0))
            }
            db.query("SELECT state,resultCollectedAt''')
s=s[:a]+part+s[b:];p.write_text(s)
