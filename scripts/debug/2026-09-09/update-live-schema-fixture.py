from pathlib import Path
p=Path('core/storage/src/androidTest/kotlin/com/helix/core/storage/RoomMigrationFixtureTest.kt')
s=p.read_text();a=s.index('    fun v10ExportMatchesTheCodeBuiltSchema()');b=s.index('\n    @Test',a)
s=s[:a]+s[a:b].replace('v10','v11').replace('db", 10)', 'db", 11)')+s[b:]
s=s.replace('[v6ExportMatchesTheCodeBuiltSchema]', '[v11ExportMatchesTheCodeBuiltSchema]')
p.write_text(s)
