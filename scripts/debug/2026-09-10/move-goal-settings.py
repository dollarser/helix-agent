from pathlib import Path
base=Path('app/src/main/kotlin/com/helix/app')
p=base/'ui/GoalDialog.kt';s=p.read_text();s=s.replace('    selectedGoalId: String? = null,','    selectedGoalId: String? = null,\n    onSettings: () -> Unit = {},');a=s.index('    var creating by remember');b=s.index('    LaunchedEffect',a);s=s[:a]+s[b:];a=s.index('    if (creating || editing != null)');b=s.index('        AlertDialog(',a);s=s[:a]+s[b:];a=s.index('                        TextButton(\n                            enabled = row.canEditBudgets');b=s.index('                        onDeleteGoal?.let',a);s=s[:a]+s[b:];s=s.replace('onClick = { creating = true },','onClick = { onDismiss(); onSettings() },').replace('Modifier.testTag("goal-create")','Modifier.testTag("goal-settings")').replace('Text(stringResource(R.string.goal_create))','Text(stringResource(R.string.goal_settings_title))');s=s.replace('        )\n    }\n}\n\n@Composable','        )\n}\n\n@Composable',1)
# Keep the reusable editor for settings, including existing-Goal extensions; fresh defaults need no fake Goal row.
s=s.replace('    onSave: suspend (String, List<String>, GoalBudgets) -> Unit,','    onSave: suspend (String, List<String>, GoalBudgets) -> Unit,\n    defaultBudgets: GoalBudgets = com.helix.app.runcontrol.GoalBudgetDefaults.VALUE,\n    budgetsOnly: Boolean = false,')
s=s.replace('GoalBudgets(32, 64, 100_000, 600_000, 300_000, 0)','defaultBudgets')
s=s.replace('val valid = validGoalDescription(objective, criterionList)','val valid = budgetsOnly || validGoalDescription(objective, criterionList)')
a=s.index('                OutlinedTextField(\n                    objective,');b=s.index('                labels.forEachIndexed',a);old=s[a:b];s=s[:a]+'''                if (!budgetsOnly) {
'''+old+'''                }
'''+s[b:];p.write_text(s)
(base/'ui/GoalSettingsSection.kt').write_text('''package com.helix.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.chat.ChatService
import com.helix.app.chat.GoalSummaryUi
import com.helix.app.runcontrol.GoalBudgetDefaults
import com.helix.app.runcontrol.RunControlStore

/** Default policy and explicit parked-Goal extensions live in settings, never the composer. */
@Composable
@Suppress("FunctionName", "LongMethod")
internal fun GoalSettingsSection(store: RunControlStore, service: ChatService? = null) {
    val config by store.flow.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<GoalSummaryUi?>(null) }
    var rows by remember { mutableStateOf<List<GoalSummaryUi>>(emptyList()) }
    var revision by remember { mutableStateOf(0) }
    LaunchedEffect(service, revision) { rows = service?.goalSummaries().orEmpty() }
    Column(Modifier.testTag("settings-goal-budgets"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.goal_settings_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.goal_settings_note), style = MaterialTheme.typography.bodySmall)
        val budgets = config.goalBudgets
        Text(stringResource(R.string.goal_defaults_summary, budgets.maxModelCalls, budgets.maxToolCalls,
            budgets.maxTotalTokens, budgets.maxDurationMillis / 60_000, budgets.maxWakeDurationMillis / 60_000))
        SettingsActions {
            OutlinedButton({ selected = null; open = true }, Modifier.testTag("goal-defaults-edit")) {
                Text(stringResource(R.string.goal_edit_budgets))
            }
            OutlinedButton({ store.setGoalBudgets(GoalBudgetDefaults.VALUE) }, Modifier.testTag("goal-defaults-reset")) {
                Text(stringResource(R.string.goal_defaults_reset))
            }
        }
        if (service != null) rows.filter { it.canEditBudgets }.forEach { row ->
            ExpandableSummary(row.objective, collapsedLines = 2)
            OutlinedButton({ selected = row; open = true }, Modifier.testTag("goal-edit-budgets-${row.id}")) {
                Text(stringResource(R.string.goal_edit_budgets))
            }
            GoalReminderControls(service, row) { revision++ }
        }
    }
    if (open) GoalEditor(selected, "", { open = false }, { _, _, budgets ->
        val row = selected
        if (row == null) store.setGoalBudgets(budgets)
        else check(requireNotNull(service).updateGoalBudgets(row.id, budgets))
        open = false
        revision++
    }, defaultBudgets = config.goalBudgets, budgetsOnly = true)
}
''')
p=base/'ui/SettingsScreen.kt';s=p.read_text().replace('    skillInstallationService: com.helix.app.skills.SkillInstallationService? = null,','    skillInstallationService: com.helix.app.skills.SkillInstallationService? = null,\n    chatService: com.helix.app.chat.ChatService? = null,').replace('        SettingsGroup { RunControlSettingsSection(runControlStore) }','        SettingsGroup { RunControlSettingsSection(runControlStore) }\n        SettingsGroup { GoalSettingsSection(runControlStore, chatService) }');p.write_text(s)
p=base/'MainActivity.kt';s=p.read_text();needle='                                        container.skillInstallationService,\n                                    )';assert needle in s;s=s.replace(needle,'                                        container.skillInstallationService,\n                                        chatService = container.chatService,\n                                    )',1);p.write_text(s)
p=base/'chat/GoalComposerStart.kt';s=p.read_text().replace('                    storage.goalRuns.listByGoal(binding.goalId)\n','');p.write_text(s)
p=base/'chat/ChatService.kt';s=p.read_text().replace('control.mode == AgentMode.GOAL && retryTurnId == null && text != null','control.mode == AgentMode.GOAL && retryTurnId == null && text != null && text != ContextCompaction.COMMAND').replace('&& goalStart == null && retryTurnId == null)', '&& goalStart == null && retryTurnId == null && text != ContextCompaction.COMMAND)');p.write_text(s)
p=base/'runcontrol/GoalBudgetDefaults.kt';s=p.read_text().replace('\n}', '''
    fun validate(budgets: GoalBudgets): GoalBudgets = budgets.also {
        require(it.maxTotalTokens > 0 && it.maxDurationMillis > 0 && it.maxWakeDurationMillis > 0)
        require(it.maxWakeDurationMillis <= it.maxDurationMillis)
    }
}
''');p.write_text(s)
p=base/'runcontrol/RunControlStore.kt';s=p.read_text().replace('listOf(budgets.toStorageString()))','listOf(GoalBudgetDefaults.validate(budgets).toStorageString()))').replace('?.let(GoalBudgets::parse) ?: GoalBudgetDefaults.VALUE','?.let { GoalBudgetDefaults.validate(GoalBudgets.parse(it)) } ?: GoalBudgetDefaults.VALUE');p.write_text(s)
strings={
'goal_settings_title':('Goal 运行设置','Goal execution settings'),
'goal_settings_note':('选择 Goal 后，直接输入任务并发送。默认值只用于新目标；当前目标的预算可在下方单独调整。累计 token 包含每次请求的输入与输出，不是上下文窗口大小；实际请求仍受模型能力与单轮预算约束。','Select Goal, type a task and send. Defaults apply only to new goals; adjust the current goal separately below. Cumulative tokens count input and output across requests, not context size. Model and per-turn limits still apply.'),
'goal_defaults_summary':('模型 %1$d 次 · 工具 %2$d 次 · 累计 %3$d token · 总执行 %4$d 分钟 · 单次 %5$d 分钟','%1$d model calls · %2$d tool calls · %3$d total tokens · %4$d execution minutes · %5$d minutes per run'),
'goal_defaults_reset':('恢复默认值','Reset defaults'),
}
for directory in ['values','values-en','values-zh-rCN']:
 p=Path('app/src/main/res')/directory/'strings.xml';s=p.read_text();insert=''.join(f'    <string name="{k}">{v[1 if directory=="values-en" else 0]}</string>\n' for k,v in strings.items());p.write_text(s.replace('</resources>',insert+'</resources>'))
