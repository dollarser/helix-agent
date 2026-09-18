"""Separate notification rendering and device shell helpers from lifecycle classes."""
from pathlib import Path
root = Path(__file__).resolve().parents[3]
path = root / 'runtime/proot-app/src/main/kotlin/com/helix/runtime/proot/app/ProotDetachedJobService.kt'
text = path.read_text()
start = text.index('    private fun notification(')
end = text.index('    private fun reject(', start)
block = '\n'.join(line[4:] if line.startswith('    ') else line for line in text[start:end].splitlines())
block = block.replace('private fun notification(', 'private fun ProotDetachedJobService.notification(')
text = text[:start] + text[end:]
text = text.replace('        private const val CHANNEL = "proot-detached"\n', '')
path.write_text(text + '\nprivate const val CHANNEL = "proot-detached"\n\n' + block + '\n')
path = root / 'app/src/androidTestDeveloper/kotlin/com/helix/app/proot/ProotDetachedJobDeviceTest.kt'
text = path.read_text()
start = text.index('    private fun shell(')
block = text[start:text.rfind('\n}')]
block = '\n'.join(line[4:] if line.startswith('    ') else line for line in block.splitlines())
path.write_text(text[:start] + '}\n\n' + block + '\n')
