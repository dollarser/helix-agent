from pathlib import Path
base=Path('app/src/androidTest/kotlin/com/helix/app/ui')
p=base/'ChatStopProgressDeviceTest.kt';s=p.read_text();a=s.index('        if (goalMode) {',s.index('private fun verifyEmptyConversation'));b=s.index('        assertTrue(',a);s=s[:a]+'''        compose.onNodeWithTag("chat-send")
            .assertContentDescriptionEquals(compose.activity.getString(R.string.common_send))
            .assertIsNotEnabled()
'''+s[b:];p.write_text(s)
p=base/'GoalDialogDeviceTest.kt';s=p.read_text().replace('fun dismissalPreservesDraftAndCreationDoesNotRunUntilExplicitContinue()', 'fun dismissalAndSettingsPreserveDraftAndContinueStillUsesAdmission()');s=s.replace('        var goalId: String? = null','''        var settingsOpened = false
        var goalId: String? = kotlinx.coroutines.runBlocking {
            service.createGoal(objective, emptyList(), com.helix.app.runcontrol.GoalBudgetDefaults.VALUE)
        }''');s=s.replace('                            continued++\n                        })','                            continued++\n                        }, onSettings = { settingsOpened = true })');a=s.index('            compose.onNodeWithTag("goal-create")');b=s.index('            val id = requireNotNull(goalId)',a);s=s[:a]+'''            compose.onNodeWithTag("goal-settings").performClick()
            compose.runOnIdle {
                assertEquals(true, settingsOpened)
                assertEquals(objective, draft)
                open = true
            }
'''+s[b:];s=s.replace('import androidx.compose.ui.test.performTextInput\n','');p.write_text(s)
p=base/'GoalReminderControlsDeviceTest.kt';s=p.read_text();old='''                        GoalDialog(
                            service,
                            "",
                            { open = false },
                            { error("Unexpected Continue") },
                            selectedGoalId = id,
                        )''';assert s.count(old)==2;s=s.replace(old,'''                        androidx.compose.foundation.layout.Column(
                            androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                        ) { GoalSettingsSection(container.runControlStore, service) }''');s=s.replace('import androidx.compose.material3.MaterialTheme','import androidx.compose.foundation.verticalScroll\nimport androidx.compose.material3.MaterialTheme');s=s.replace('                compose.onNodeWithTag("goal-close").performClick()\n                compose.runOnIdle { open = true }','                compose.runOnIdle { open = false }\n                compose.runOnIdle { open = true }');p.write_text(s)
p=base/'GoalRealModelUiDeviceTest.kt';s=p.read_text();s=s.replace('                assertRunCount(0)\n                continueAndVerify(1, "GOAL_UI_FIRST")','                continueAndVerify(1, "GOAL_UI_FIRST", start = false)');s=s.replace('                compose.onNodeWithTag("chat-input").performTextInput("Reply only GOAL_UI_SECOND. Do not call tools.")\n','');s=s.replace('                compose.onNodeWithTag("goal-edit-budgets-$goalId").performScrollTo().performClick()','                compose.onNodeWithTag("goal-settings").performClick()\n                compose.onNodeWithTag("goal-edit-budgets-$goalId").performScrollTo().performClick()');s=s.replace('                assertRunCount(1)\n                continueAndVerify','                assertRunCount(1)\n                compose.navigateTo("sessions")\n                compose.onNodeWithTag("chat-input").performTextInput("Reply only GOAL_UI_SECOND. Do not call tools.")\n                compose.onNodeWithTag("goal-manage").performClick()\n                continueAndVerify');s=s.replace('                service.setMode(previous.mode)','                container.runControlStore.setGoalBudgets(previous.goalBudgets)\n                service.setMode(previous.mode)');a=s.index('        compose.onNodeWithTag("chat-input").performTextInput(objective)',s.index('private fun createGoalThroughUi'));b=s.index('        compose.waitUntil(10_000) {',a);s=s[:a]+'''        compose.navigateTo("settings")
        compose.onNodeWithTag("goal-defaults-edit").performScrollTo().performClick()
        replace("goal-budget-0", "1")
        compose.onNodeWithTag("goal-save").performClick()
        compose.navigateTo("sessions")
        compose.onNodeWithTag("chat-input").performTextInput(objective)
        compose.onNodeWithTag("chat-send").performClick()
'''+s[b:];s=s.replace('        assertEquals("READY", storage.goals.resolve(requireNotNull(goalId)).state)\n','');s=s.replace('        marker: String,\n    ) {\n        compose','        marker: String,\n        start: Boolean = true,\n    ) {\n        if (start) compose');p.write_text(s)
strings={
'goal_defaults_summary':('模型调用：%1$d · 工具调用：%2$d · 累计 token：%3$d · 总执行（分钟）：%4$d · 单次（分钟）：%5$d','Model calls: %1$d · Tool calls: %2$d · Total tokens: %3$d · Execution (min): %4$d · Per run (min): %5$d'),
'chat_empty_goal':('输入你希望完成的任务，发送即可创建并运行 Goal。运行额度可在设置中调整。','Describe the task and send to create and run a Goal. Execution limits are available in Settings.'),
'goal_explicit_continue':('在输入框发送任务即可创建 Goal；继续会恢复当前目标，修改预算只会保存。模型报告进展与完成情况。','Send a task from the composer to create a Goal. Continue resumes it; budget edits only save. The model reports progress and completion.'),
}
import re
for directory in ['values','values-en','values-zh-rCN']:
 p=Path('app/src/main/res')/directory/'strings.xml';s=p.read_text()
 for name,text in strings.items(): s=re.sub(r'(<string name="'+name+r'">).*?(</string>)',lambda m:m[1]+text[1 if directory=='values-en' else 0]+m[2],s)
 p.write_text(s)
p=Path('scripts/debug/2026-09-10/verify-goal-entry.py');s=p.read_text().replace("'GoalComposerStartDeviceTest.kt']","'GoalComposerStartDeviceTest.kt','GoalDialogDeviceTest.kt','GoalReminderControlsDeviceTest.kt','GoalRealModelUiDeviceTest.kt','ChatStopProgressDeviceTest.kt']");p.write_text(s)
