from pathlib import Path
p=Path('core/agent/src/main/kotlin/com/helix/core/agent/Goal.kt');s=p.read_text().replace('        require(criteria.isNotEmpty()) { "criteria must not be empty" }\n','');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/ui/GoalDialog.kt');s=p.read_text().replace('criteria.size in 1..32','criteria.size in 0..32');p.write_text(s)
for locale,label in [('values','补充要求（可选，每行一项）'),('values-en','Additional requirements (optional, one per line)'),('values-zh-rCN','补充要求（可选，每行一项）')]:
 p=Path('app/src/main/res',locale,'strings.xml');s=p.read_text();import re
 s=re.sub(r'(<string name="goal_criteria">).*?(</string>)',lambda m:m[1]+label+m[2],s);p.write_text(s)
p=Path('app/src/androidTest/kotlin/com/helix/app/ui/GoalEditorDeviceTest.kt');s=p.read_text().replace('creationRequiresCriteriaAndSavesOnlyAfterExplicitClick','objectiveOnlyGoalSavesOnlyAfterExplicitClick').replace('assertEquals(listOf("Output has been verified"), criteria)','assertTrue(criteria.isEmpty())').replace('        compose.onNodeWithTag("goal-save").assertIsNotEnabled()\n        compose.onNodeWithTag("goal-criteria").performTextInput("Output has been verified")\n','');p.write_text(s)
p=Path('app/src/androidTest/kotlin/com/helix/app/ui/GoalModelReportFlowDeviceTest.kt');s=p.read_text().replace('listOf("Give the correct answer clearly")','emptyList()');p.write_text(s)
p=Path('core/agent/src/test/kotlin/com/helix/core/agent/GoalModelCompletionTest.kt');s=p.read_text();idx=s.index('    @Test');s=s[:idx]+'''    @Test fun objectiveOnlyGoalCanComplete() {
        val running = runningGoal().copy(criteria = emptyList())
        assertEquals(GoalState.COMPLETED, GoalReducer.reduce(running, GoalEvent.CompleteRequested).state.state)
    }

'''+s[idx:];p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/goal/GoalReportContext.kt');s=p.read_text().replace('        Success criteria:','        Additional requirements (if any):');p.write_text(s)
p=Path('docs/adr/0040-model-judged-goal-completion.md');s=p.read_text().replace('开放目标无需手工绑定','只填写目标即可创建，补充要求为可选描述。开放目标无需手工绑定');p.write_text(s)
