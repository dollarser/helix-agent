"""Copy this task's explicit ownership set into the verified free attached worktree."""
import hashlib
import json
from pathlib import Path
import shutil

source = Path.cwd()
target = Path.home() / '.codex/worktrees/p5-harness-closeout/Helix'
paths = [
'app/src/androidTestDeveloper/kotlin/com/helix/app/eval/AndroidWorldPilotDeviceTest.kt',
'app/src/androidTestDeveloper/kotlin/com/helix/app/eval/EvaluationMetadata.kt',
'app/src/main/kotlin/com/helix/app/chat/ChatRequestAssembler.kt',
'app/src/main/kotlin/com/helix/app/chat/ModelToolExposureOrder.kt',
'app/src/main/kotlin/com/helix/app/mcp/McpToolDiscovery.kt',
'app/src/test/kotlin/com/helix/app/chat/ModelToolExposureOrderTest.kt',
'app/src/test/kotlin/com/helix/app/mcp/McpToolDiscoveryTest.kt',
'docs/adr/mcp/001-client-and-discovery.md',
'docs/architecture/connector-portability.md',
'docs/evidence/development/public-agent-pilot-2026-09-28.md',
'scripts/debug/2026-09-28/run-androidworld-pilot.py',
'scripts/debug/2026-09-28/run-bfcl-diagnostic.py',
'scripts/debug/2026-09-28/summarize-public-eval.py',
'scripts/debug/2026-09-28/isolate-public-eval-work.py',
 'tools/automation/src/main/kotlin/com/helix/tools/automation/AutomationTools.kt',
 'tools/automation/src/test/kotlin/com/helix/tools/automation/AutomationToolsTest.kt',
]
manifest = {}
for name in paths:
    data = (source / name).read_bytes()
    destination = target / name
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(data)
    manifest[name] = hashlib.sha256(data).hexdigest()
(target / 'build').mkdir(exist_ok=True)
shared = target / 'build/public-eval'
assert not shared.exists()
shared.symlink_to(source / 'build/public-eval', target_is_directory=True)
(target / 'build/public-eval-isolation.json').write_text(json.dumps(manifest, indent=2))
if (source / 'local.properties').exists():
    shutil.copy2(source / 'local.properties', target / 'local.properties')
print(f'Copied {len(paths)} owned paths; source worktree unchanged.')
