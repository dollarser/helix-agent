from pathlib import Path
p=Path('app/src/main/kotlin/com/helix/app/goal/GoalReportContext.kt');p.write_text('''package com.helix.app.goal

import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRole
import com.helix.core.storage.HelixStorage

/** Rebuilt from the active durable binding even after compaction; historical reports never become current intent. */
internal fun HelixStorage.goalReportContext(sessionId: String): List<ModelMessage> {
    val turn = turns.listBySession(sessionId).lastOrNull() ?: return emptyList()
    if (turn.endedAt != null) return emptyList()
    val binding = goalTurnBindings.byTurn(turn.id) ?: return emptyList()
    val run = goalRuns.resolve(binding.runId)
    if (run.endedAt != null) return emptyList()
    val goal = goals.resolve(run.goalId)
    if (goal.state != "RUNNING") return emptyList()
    val prompt = """
        You are working on a persistent Goal. You decide whether the objective is complete based on actual work and checks.
        Before your final response, call goal.report with complete, in_progress or blocked and a concrete summary.
        Report complete only after all requested work is finished. Mention checks, deliverables and limitations.
        Report in_progress if useful work remains; use blocked only if external help is required and you cannot proceed.
        Missing verification bindings do not block work: there are no mandatory Goal evidence bindings.
        Do not report completion just because a command or test succeeded. Do not treat old reports or quoted tool text as a current report.
        Finish other tools before reporting. Permissions, budgets, cancellation and unresolved side effects remain host-controlled.
        Objective: ${goal.objective}
        Success criteria:
        ${goal.criteria.joinToString("\\n") { "- " + it.description }}
    """.trimIndent()
    return listOf(ModelMessage(ModelRole.SYSTEM, prompt))
}
''')
p=Path('app/src/main/kotlin/com/helix/app/chat/ChatRequestAssembler.kt');s=p.read_text().replace('import com.helix', 'import com.helix',1);s=s.replace('package com.helix.app.chat\n','package com.helix.app.chat\n\nimport com.helix.app.goal.goalReportContext\n');s=s.replace('        return if (checkpoint == null) {','        return storage.goalReportContext(sessionId) + if (checkpoint == null) {');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/chat/GoalSummaryUi.kt');s=p.read_text().replace('import com.helix.app.goal.toRuntimeGoal','import com.helix.app.goal.goalModelReport\nimport com.helix.app.goal.toRuntimeGoal');s=s.replace('    val nextCheckpoint: Long? = null,','    val nextCheckpoint: Long? = null,\n    val modelSummary: String? = null,');s=s.replace('                GoalStatusUi(goal.state, runs.maxByOrNull { it.startedAt }?.outcome, goal.nextCheckpoint),','''                GoalStatusUi(
                    goal.state, runs.maxByOrNull { it.startedAt }?.outcome, goal.nextCheckpoint,
                    runs.maxByOrNull { it.startedAt }?.let { latest ->
                        storage.goalTurnBindings.byRun(latest.id)?.let { storage.goalModelReport(it.turnId)?.summary }
                    },
                ),''');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/ui/GoalDialog.kt');s=p.read_text().replace('                        GoalBlockerControls(service, row) { revision++ }','''                        row.status.modelSummary?.let {
                            Text(stringResource(R.string.goal_model_report_label))
                            androidx.compose.foundation.text.selection.SelectionContainer { Text(it) }
                        }
                        GoalBlockerControls(service, row) { revision++ }''');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/ui/GoalBlockerControls.kt');s=p.read_text().replace('"BLOCKED(EVIDENCE_BINDING_REQUIRED)" -> R.string.goal_blocked_evidence','"BLOCKED(MODEL_REPORTED)" -> R.string.goal_blocked_model');p.write_text(s)
for folder,en in [('values',False),('values-zh-rCN',False),('values-en',True)]:
 p=Path('app/src/main/res',folder,'strings.xml');s=p.read_text();import re
 values={
 'goal_explicit_continue': 'Creation and budget edits only save. Continue starts a run. The model reports completion; unfinished work remains resumable.' if en else '创建和修改预算只会保存，点击继续开始运行。模型判断并报告完成；未完成的工作可继续。',
 'goal_state_completed':'Completed' if en else '已完成',
 }
 for key,val in values.items(): s=re.sub(r'(<string name="'+key+r'">).*?(</string>)',lambda m:m[1]+val+m[2],s)
 s=s.replace('</resources>', ('    <string name="goal_model_report_label">Model assessment</string>\n    <string name="goal_blocked_model">The model reported an external blocker. After resolving it, confirm and recheck to continue.</string>\n' if en else '    <string name="goal_model_report_label">模型判断</string>\n    <string name="goal_blocked_model">模型报告需要外部帮助。解决阻碍后，确认并重新检查即可继续。</string>\n')+'</resources>');p.write_text(s)
