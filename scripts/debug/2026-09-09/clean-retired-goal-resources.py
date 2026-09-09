from pathlib import Path
import re
report=Path('app/build/intermediates/lint_intermediate_text_report/developerDebug/lintReportDeveloperDebug/lint-results-developerDebug.txt').read_text()
names=set(re.findall(r'R.string.(goal_\w+) appears to be unused',report))
for p in Path('app/src/main/res').glob('values*/strings.xml'):
 s=p.read_text()
 for n in names:
  s=re.sub(r'    <string name="'+n+r'">.*?</string>\n','',s)
 p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/goal/GoalReportTool.kt')
s=p.read_text().replace('                if (!active || !owned || storage.goals.resolve(requireNotNull(run).goalId).state != "RUNNING") {','                val running = run?.let { storage.goals.resolve(it.goalId).state == "RUNNING" } == true\n                if (!active || !owned || !running) {')
p.write_text(s)
