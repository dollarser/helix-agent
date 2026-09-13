"""Keep changed file mutation contract versions and text consistent with root-file support."""
from pathlib import Path
import re
base = Path('tools/files/src/main/kotlin/com/helix/tools/files')
for name in ['WriteTool', 'EditTool', 'FilesMkdirTool', 'FilesCopyTool', 'FilesMoveTool', 'FilesDeleteTool']:
    path = base / (name + '.kt')
    text = path.read_text()
    text, count = re.subn(r'(const val VERSION(?:: Int)? = )1\b', r'\g<1>2', text)
    assert count == 1, name
    text = text.replace('(input/, work/ or output/)', '(workspace files; .helix/ is reserved)')
    text = text.replace('destination must be inside input/, work/ or output/', 'destination must be a user file or directory, outside .helix/')
    path.write_text(text)
