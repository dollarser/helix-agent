from pathlib import Path
base=Path('app/src/main/kotlin/com/helix/app/ui')
p=base/'GoalDialog.kt';s=p.read_text();a=s.index('internal fun GoalEditor(');b=s.index('    var fields by remember',a);s=s[:a]+'''internal fun GoalEditor(
    initialBudget: GoalBudgets,
    onDismiss: () -> Unit,
    onSave: suspend (GoalBudgets) -> Unit,
) {
    val scope = rememberCoroutineScope()
'''+s[b:];a=s.index('    val criterionList =');b=s.index('    val labels =',a);s=s[:a]+s[b:];a=s.index('        title = {',s.index('internal fun GoalEditor'));b=s.index('        text = {',a);s=s[:a]+'''        title = { Text(stringResource(R.string.goal_edit_budgets)) },
'''+s[b:];a=s.index('                if (!budgetsOnly) {');b=s.index('                labels.forEachIndexed',a);s=s[:a]+s[b:];s=s.replace('enabled = valid && budgets != null && !saving','enabled = budgets != null && !saving').replace('onSave(objective.trim(), criterionList, requireNotNull(budgets))','onSave(requireNotNull(budgets))');a=s.index('private fun validGoalDescription(');b=s.index('internal fun parseGoalBudgetFields',a);s=s[:a]+s[b:];p.write_text(s)
p=base/'GoalSettingsSection.kt';s=p.read_text();a=s.index('    if (open) {');s=s[:a]+'''    if (open) {
        GoalEditor(selected?.budgets ?: config.goalBudgets, { open = false }) { budgets ->
            val row = selected
            if (row == null) store.setGoalBudgets(budgets)
            else check(requireNotNull(service).updateGoalBudgets(row.id, budgets))
            open = false
            revision++
        }
    }
}
''';p.write_text(s)
p=Path('app/src/androidTest/kotlin/com/helix/app/ui/GoalEditorDeviceTest.kt');s=p.read_text();s=s.replace('fun objectiveOnlyGoalSavesOnlyAfterExplicitClick()', 'fun budgetDefaultsSaveOnlyAfterExplicitClick()');a=s.index('                GoalEditor(null,');b=s.index('                    saved = budgets',a);s=s[:a]+'''                GoalEditor(com.helix.app.runcontrol.GoalBudgetDefaults.VALUE, {}, { budgets ->
'''+s[b:];s=s.replace('GoalEditor(null, "Check an output", {}, { _, _, _ -> save() })','GoalEditor(com.helix.app.runcontrol.GoalBudgetDefaults.VALUE, {}, { save() })').replace('        compose.onNodeWithTag("goal-criteria").performTextInput("Output has been verified")\n','').replace('import androidx.compose.ui.test.performTextInput\n','').replace('import androidx.compose.ui.test.assertIsNotEnabled\n','');p.write_text(s)
for p in Path('app/src/main/res').glob('values*/strings.xml'):
 s=p.read_text();s='\n'.join(line for line in s.split('\n') if not any(f'<string name="{k}">' in line for k in ['goal_create','goal_objective','goal_criteria']));p.write_text(s)
