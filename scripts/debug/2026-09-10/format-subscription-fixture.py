"""Wrap only the synthetic PNG literal without changing its bytes."""
from pathlib import Path
import re
p = Path('app/src/main/kotlin/com/helix/app/provider/VisionImageSource.kt')
s = p.read_text()
pattern = r'private val COLOR_PROBE_IMAGE = LoadedImage\("image/png", "([A-Za-z0-9+/=]+)"\)'
def wrap(match):
    value = match.group(1)
    parts = [value[i:i + 80] for i in range(0, len(value), 80)]
    return 'private val COLOR_PROBE_IMAGE = LoadedImage("image/png",\n' + ' +\n'.join(
        '            "' + part + '"' for part in parts) + ')'
s, n = re.subn(pattern, wrap, s)
assert n == 1
p.write_text(s)
