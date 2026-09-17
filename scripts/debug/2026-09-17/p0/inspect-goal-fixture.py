"""Read only synthetic Goal lifecycle rows; never print provider or account data."""
import pathlib, subprocess, sqlite3, sys
root = pathlib.Path('build/p0-resume/goal-fixture-db')
root.mkdir(parents=True, exist_ok=True)
for name in ['helix.db', 'helix.db-wal']:
    with (root / name).open('wb') as out:
        subprocess.run(['adb', '-s', sys.argv[1], 'exec-out', 'run-as', 'com.helix.agent.developer', 'cat', 'databases/' + name], stdout=out, check=True)
connection = sqlite3.connect(root / 'helix.db')
for row in connection.execute("select t.id,t.state,t.errorCode,t.pauseRequestedAt from turns t join sessions s on s.id=t.sessionId where s.title='Goal lifecycle fixture' order by t.rowid desc limit 8"):
    print(row)
for row in connection.execute("select g.state,g.modelCalls,g.toolCalls,g.error,g.finishReason from goals g join goal_controls c on c.goalId=g.id join sessions s on s.id=c.sessionId where s.title='Goal lifecycle fixture' order by g.rowid desc limit 8"):
    print(row)
connection.close()
for p in root.iterdir(): p.unlink()
root.rmdir()
