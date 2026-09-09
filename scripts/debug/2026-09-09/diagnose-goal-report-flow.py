from pathlib import Path
archive=Path('scripts/debug/2026-09-09/retired-goal-verifier')
for n in ['EditedArtifactContentTest','WrittenArtifactContentTest']:
 p=Path('app/src/test/kotlin/com/helix/app/goal',n+'.kt')
 if p.exists(): (archive/(str(p).replace('/','__')+'.txt')).write_bytes(p.read_bytes());p.unlink()
p=Path('app/src/androidTest/kotlin/com/helix/app/ui/GoalModelReportFlowDeviceTest.kt');s=p.read_text().replace('        assertEquals(expected, storage.goals.resolve(goal).state)','''        val diagnostic = storage.turns.listBySession(session).joinToString { "${it.state}:${it.errorCode}" }
        assertEquals("$diagnostic / ${storage.goals.resolve(goal).error}", expected, storage.goals.resolve(goal).state)''');p.write_text(s)
