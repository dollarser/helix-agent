from pathlib import Path
files={
'app/src/main/kotlin/com/helix/app/goal/GoalReportTool.kt':{'com.helix.tools.framework':['ToolRegistry','ToolImplementationRegistry','ToolDescriptor','Idempotency','ToolOrigin','ToolExecutor','ExecutableToolCall','ToolExecutorResult'],'kotlinx.serialization.json':['Json','jsonObject','jsonPrimitive']},
'app/src/androidTest/kotlin/com/helix/app/chat/GoalModelReportDeviceTest.kt':{'com.helix.core.model':['Clock','GoalBudgets','TurnState','TurnBudgets'],'kotlinx.serialization.json':['buildJsonObject','put'],'org.junit.Assert':['assertEquals','assertTrue','assertFalse','assertNull']},
'core/agent/src/test/kotlin/com/helix/core/agent/GoalModelCompletionTest.kt':{'org.junit.Assert':['assertEquals','assertTrue','assertThrows']},
}
for name, groups in files.items():
 p=Path(name);s=p.read_text()
 for package, names in groups.items(): s=s.replace('import '+package+'.*','\n'.join('import '+package+'.'+n for n in names))
 p.write_text(s)
p=Path('core/storage/src/androidTest/kotlin/com/helix/core/storage/RoomMigrationFixtureTest.kt');s=p.read_text();a=s.index('    fun v11ExportMatchesTheCodeBuiltSchema');b=s.index('\n    @Test',a);s=s[:a]+s[a:b].replace('v11','v12').replace('db", 11)','db", 12)')+s[b:];s=s.replace('[v11ExportMatchesTheCodeBuiltSchema]','[v12ExportMatchesTheCodeBuiltSchema]').replace('                    HelixDatabase.MIGRATION_10_11,','                    HelixDatabase.MIGRATION_10_11,\n                    HelixDatabase.MIGRATION_11_12,');p.write_text(s)
