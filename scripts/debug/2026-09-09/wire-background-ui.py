"""HXA-177 UI/source wiring; run from checkout root."""
from pathlib import Path


def edit(path, old, new):
    p = Path(path)
    s = p.read_text()
    assert old in s, (path, old)
    p.write_text(s.replace(old, new))


service = 'app/src/main/kotlin/com/helix/app/chat/ChatService.kt'
fields = '    private val _backgroundTasks = MutableStateFlow<List<BackgroundTaskUi>>(emptyList())\n    val backgroundTasks: StateFlow<List<BackgroundTaskUi>> = _backgroundTasks\n'
edit(service, fields, '')
edit(service, '    private val _screen = MutableStateFlow(EMPTY_SCREEN)', fields + '    private val _screen = MutableStateFlow(EMPTY_SCREEN)')
edit(service, '    internal suspend fun updateGoalBudgets(', '''    internal suspend fun recheckGoalBlocker(goalId: String): Boolean = withContext(Dispatchers.IO) {
        val session = resolvableOpenSessionId() ?: return@withContext false
        val goal = storage.goals.resolve(goalId)
        val fits = requestAssembler.contextFits(session, runControlStore.flow.value, goal.objective)
        GoalBlockerResolution(storage).resolve(goalId, session, fits)
    }

    internal suspend fun updateGoalBudgets(''')
edit('app/src/main/kotlin/com/helix/app/chat/ChatRequestAssembler.kt', '    fun contextFits(', '    suspend fun contextFits(')
edit('app/src/main/kotlin/com/helix/app/chat/GoalSummaryUi.kt',
     'goal.state in setOf("PAUSED", "INPUT_REQUIRED")', 'goal.state in setOf("PAUSED", "INPUT_REQUIRED", "BLOCKED")')
edit('app/src/main/kotlin/com/helix/app/goal/GoalCriterionAccess.kt',
     'GoalState.PAUSED, GoalState.INPUT_REQUIRED)', 'GoalState.PAUSED, GoalState.INPUT_REQUIRED, GoalState.BLOCKED)')
screen = 'app/src/main/kotlin/com/helix/app/ui/ChatScreen.kt'
edit(screen, '    var goalsOpen by remember { mutableStateOf(false) }', '''    var goalsOpen by remember { mutableStateOf(false) }
    var tasksOpen by remember { mutableStateOf(false) }
    if (tasksOpen) BackgroundTaskDialog(chatService, onDismiss = { tasksOpen = false })''')
edit(screen, '                onRestore = chatService::restoreSession,', '                onRestore = chatService::restoreSession,\n                onTasks = { tasksOpen = true },')
edit(screen, '                        onManageGoal = { goalsOpen = true },', '                        onManageGoal = { goalsOpen = true },\n                        onTasks = { tasksOpen = true },')
edit(screen, '    onRestore: (String) -> Unit,', '    onRestore: (String) -> Unit,\n    onTasks: () -> Unit,')
edit(screen, '                OutlinedButton(\n                    onClick = onNew,', '''                TextButton(onTasks, modifier = Modifier.testTag("background-tasks-open")) {
                    Text(stringResource(R.string.background_tasks))
                }
                OutlinedButton(
                    onClick = onNew,''')
edit(screen, '    val onNew: () -> Unit = {},', '    val onNew: () -> Unit = {},\n    val onTasks: () -> Unit = {},')
edit(screen, '            onRename = intents.onRename,', '            onRename = intents.onRename,\n            onTasks = intents.onTasks,')
header = 'app/src/main/kotlin/com/helix/app/ui/AdaptiveConversationHeader.kt'
edit(header, '    onNew: () -> Unit = {},', '    onNew: () -> Unit = {},\n    onTasks: () -> Unit = {},')
edit(header, '        IconButton(onBack,', '''        TextButton(onTasks, modifier = Modifier.testTag("background-tasks-open")) {
            Text(stringResource(R.string.background_tasks))
        }
        IconButton(onBack,''')
edit('app/src/main/kotlin/com/helix/app/ui/GoalDialog.kt',
     '                        goalPauseLabel(row.status.outcome)?.let { Text(stringResource(it)) }', '''                        goalPauseLabel(row.status.outcome)?.let { Text(stringResource(it)) }
                        GoalBlockerControls(service, row) { revision++ }''')
edit('app/src/main/kotlin/com/helix/app/ui/GoalDialog.kt',
     '        "PAUSED" -> R.string.goal_state_paused', '        "PAUSED" -> R.string.goal_state_paused\n        "BLOCKED" -> R.string.goal_state_blocked')
edit('app/src/main/kotlin/com/helix/app/ui/GoalDialog.kt',
     '        outcome == "RUN_FINISHED" ->', '        outcome == "USER_PAUSED" -> R.string.goal_user_paused\n        outcome == "RUN_FINISHED" ->')
strings = {
    'background_tasks': ('任务', 'Tasks'),
    'background_tasks_empty': ('暂无任务', 'No tasks yet'),
    'background_tasks_hint': ('任务在各自会话运行。切换会话不会停止任务；查看结果不会再次执行。', 'Tasks run in their own conversations. Switching conversations does not stop them; viewing results does not execute them again.'),
    'background_tasks_active': ('进行中', 'Active'),
    'background_tasks_results': ('待回收', 'Uncollected'),
    'background_tasks_history': ('已回收', 'Collected'),
    'background_task_open': ('打开会话', 'Open conversation'),
    'background_task_collect': ('确认回收', 'Collect result'),
    'background_task_pause': ('暂停', 'Pause'),
    'background_task_cancel': ('取消任务', 'Cancel task'),
    'background_task_pausing': ('正在暂停，等待执行结算', 'Pausing; settling execution'),
    'goal_state_blocked': ('已阻塞', 'Blocked'),
    'goal_user_paused': ('用户已暂停，可点击继续恢复', 'Paused by you; Continue resumes the goal'),
    'goal_blocked_evidence': ('请先配置完成条件的验证方式，再重新检查。', 'Configure verification bindings for the completion criteria, then recheck.'),
    'goal_blocked_review': ('存在尚未确定的执行结果，请先检查并处理。', 'Some execution outcomes are uncertain. Inspect and reconcile them first.'),
    'goal_blocked_context': ('上下文容量不足，请先压缩会话或调整模型与上下文设置。', 'Context capacity is insufficient. Compact the conversation or adjust the model and context settings.'),
    'goal_blocked_budget': ('剩余预算不足，请调整预算后重新检查。', 'Insufficient remaining budget. Adjust the budget, then recheck.'),
    'goal_blocked_recheck': ('已处理，重新检查', 'Recheck after repair'),
    'goal_blocked_unchanged': ('阻碍尚未解除，未启动新的执行。', 'The blocker remains. No new execution was started.'),
}
for directory in ('values', 'values-zh-rCN', 'values-en'):
    file = Path('app/src/main/res') / directory / 'strings.xml'
    additions = ''.join(f'    <string name="{key}">{value[directory == "values-en"]}</string>\n' for key, value in strings.items())
    file.write_text(file.read_text().replace('</resources>', additions + '</resources>'))
