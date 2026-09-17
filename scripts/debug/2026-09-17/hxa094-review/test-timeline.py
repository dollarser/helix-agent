"""Reject empty recovery evidence; accept only sampled verification with no app su."""
import importlib.util
from pathlib import Path
import tempfile

path = Path('scripts/debug/2026-09-16/hxa094-095-timeline.py')
spec = importlib.util.spec_from_file_location('timeline', path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
for content, passes in [
    ('', False),
    ('CYCLE|1||\nROW|1 0 1 init\n', False),
    ('CYCLE|1|20|verify\nROW|20 1 20 app\n', True),
    ('CYCLE|1|20|verify\nROW|20 1 20 app\nROW|30 20 30 su\n', False),
]:
    with tempfile.NamedTemporaryFile(mode='w') as fixture:
        fixture.write(content)
        fixture.flush()
        try:
            module.check_verify(fixture.name)
            result = True
        except SystemExit:
            result = False
        assert result == passes, content
print('PASS: four recovery timeline cases')
