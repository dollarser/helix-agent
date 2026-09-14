"""Read only latest-session states and tool-envelope shapes; no credentials, prompts or full tool payloads."""
import os,subprocess,shlex,json
from pathlib import Path
adb=str(Path(os.environ['ANDROID_HOME'])/'platform-tools/adb'); serial=os.environ['HELIX_PHONE_SERIAL']
def shell(*args):return subprocess.check_output([adb,'-s',serial,'shell',shlex.join(args)],text=True,timeout=15)
def query(sql):return json.loads(shell('run-as','com.helix.agent.developer','/system/bin/sqlite3','-readonly','-json','databases/helix.db',sql) or '[]')
turns=query('select id,sessionId,state,stepCount,errorCode,startedAt from turns order by startedAt desc limit 8;')
print(json.dumps({'turns':turns}))
sid=turns[0]['sessionId'];assert sid.isalnum()
rows=query(f"select id,turnId,role,kind,contentRef,sequence from messages where sessionId='{sid}' order by sequence;")
print(json.dumps({'rows':rows}))
print(json.dumps({'tools':query(f"select t.callId,t.name,t.state,length(t.argsJson) as argsLength from tool_calls t join turns r on t.turnId=r.id where r.sessionId='{sid}';")}))
for row in rows:
    if row['kind'] not in ['TOOL_CALLS', 'TOOL_RESULT']:
        continue
    ref = json.loads(row['contentRef'])
    path = ref['path']
    assert path.startswith('content/') and '..' not in path
    body = json.loads(shell('run-as', 'com.helix.agent.developer', 'cat', 'files/helix-content/' + path))
    if row['kind'] == 'TOOL_CALLS':
        print(json.dumps({'calls': [{'name': c['name'], 'argumentsLength': len(c['arguments']), 'argumentsBlank': not c['arguments'].strip()} for c in body]}))
    else:
        print(json.dumps({'tool': body['tool'], 'status': body['status'], 'summary': body.get('summary', '')[:300]}))
