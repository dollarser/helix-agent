"""Update assertions for the authorized v2 root-file contract; preserve all rejection cases."""
from pathlib import Path
base = Path('tools/files/src/test/kotlin/com/helix/tools/files')
for name in ['WriteToolTest', 'EditToolTest', 'FilesMetaToolsTest', 'FilesMutateToolsTest']:
    path = base / (name + '.kt')
    text = path.read_text().replace('contains("input/, work/ or output/")', 'contains("outside .helix/")')
    expected = 'if (d.name.value == "files.mkdir") 2 else 1' if name == 'FilesMetaToolsTest' else '2'
    text = text.replace('assertEquals(1, d.version.value)', f'assertEquals({expected}, d.version.value)')
    path.write_text(text)
