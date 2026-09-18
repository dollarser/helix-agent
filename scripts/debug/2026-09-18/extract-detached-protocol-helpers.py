"""Keep protocol encoding and reusable test polling separate from lifecycle ownership."""
from pathlib import Path
root = Path(__file__).resolve().parents[3]
path = root / 'runtime/proot-app/src/main/kotlin/com/helix/runtime/proot/app/ProotDetachedJobService.kt'
text = path.read_text()
start = text.index('    private fun writeRecord(')
end = text.index('    private fun control(', start)
block = '\n'.join(line[4:] if line.startswith('    ') else line for line in text[start:end].splitlines())
path.write_text(text[:start] + text[end:] + '\n' + block + '\n')
path = root / 'app/src/androidTestDeveloper/kotlin/com/helix/app/proot/ProotDetachedJobDeviceTest.kt'
text = path.read_text()
start = text.index('    private fun awaitTerminal(')
end = text.index('\n}\n', start)
block = '\n'.join(line[4:] if line.startswith('    ') else line for line in text[start:end].splitlines())
block = block.replace('private fun awaitTerminal(', 'private fun DetachedJobClient.awaitTerminal(').replace('client.query(', 'query(')
text = (text[:start] + text[end:]).replace('awaitTerminal(', 'client.awaitTerminal(')
path.write_text(text + '\n' + block + '\n')
