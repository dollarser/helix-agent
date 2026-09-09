from pathlib import Path
p=Path('app/src/main/kotlin/com/helix/app/goal/GoalReportTool.kt');s=p.read_text();a=s.index('            description =');b=s.index('            inputSchema =',a);s=s[:a]+'''            description = """
                Report the current Goal status after checking your work.
                You judge whether the objective is complete. Include results,
                checks and remaining limitations in summary. Use in_progress
                when useful work remains; blocked only if external help is needed.
                Finish other tools before reporting, then explain the result.
                This report does not grant permissions.
            """.trimIndent(),
'''+s[b:];p.write_text(s)
