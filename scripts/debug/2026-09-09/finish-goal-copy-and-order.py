from pathlib import Path
for f in ['GoalBlockerResolution.kt','ChatService.kt']:
 p=Path('app/src/main/kotlin/com/helix/app/chat',f);s=p.read_text().replace('.maxByOrNull { it.startedAt }','.lastOrNull()');p.write_text(s)
for locale in ['values','values-zh-rCN','values-en']:
 p=Path('app/src/main/res',locale,'strings.xml');s=p.read_text().replace('在下方描述目标，然后打开目标面板，设置验收条件与预算并显式继续。','描述目标，在目标面板确认预算后点击继续。补充要求可选，模型会报告进展与完成情况。').replace('Describe your goal below, then open the goal panel to set completion criteria and budgets and explicitly continue.','Describe your goal, review its budget in the goal panel, then tap Continue. Additional requirements are optional; the model reports progress and completion.');p.write_text(s)
