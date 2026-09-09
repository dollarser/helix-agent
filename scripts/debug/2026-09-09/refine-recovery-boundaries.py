from pathlib import Path
p=Path('app/src/main/kotlin/com/helix/app/files/ManualTransferJournal.kt');s=p.read_text().replace('            }.toList()', '            }.iterator().asSequence().toList()');p.write_text(s)
p=Path('app/src/test/kotlin/com/helix/app/files/ManualTransferRecoveryTest.kt');s=p.read_text().replace('import org.junit.Assert.*','import org.junit.Assert.assertEquals\nimport org.junit.Assert.assertFalse\nimport org.junit.Assert.assertThrows\nimport org.junit.Assert.assertTrue').replace('Files.writeString(path, Files.readString(path).replace("version=1", "version=2"))','Files.write(path, String(Files.readAllBytes(path)).replace("version=1", "version=2").toByteArray())');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/files/ManualFileOperations.kt');s=p.read_text().replace('        if (journal == null && move && !existed && sameParent)', '        val fastRename = move && !existed && sameParent\n        if (journal == null && fastRename)');s=s.replace('            if (!published) {\n                try {', '''            if (!published) {
                try {
                    if (record.phase >= ManualTransferPhase.PUBLISHING &&
                        to.stat(temporary) == null && to.stat(target.relativePath) != null) {
                        error("Publication may have completed; recover the recorded operation")
                    }''');s=s.replace('if (backedUp && to.stat(target.relativePath) == null)', 'if (to.stat(backup) != null && to.stat(target.relativePath) == null)');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/files/FileManagerService.kt');s=p.read_text();a=s.index('    private fun sha256Hex(');b=s.index('    private fun joinPath(',a);s=s[:a]+s[b:];p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/files/FileManagerPreview.kt');s=p.read_text().replace('internal class FileManagerPreview','@Suppress("TooManyFunctions") // One bounded read responsibility with platform-specific adapters.\ninternal class FileManagerPreview');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/ui/FilesScreen.kt');s=p.read_text().replace('''            Column(Modifier.fillMaxSize()) {
                FilesRecoveryPanel(actions)
                Box(Modifier.weight(1f)) { FilesScreenLayout(state, actions, openSharedStorage) }
            }''','            FilesRecoverableLayout(state, actions, openSharedStorage)');s+='''
@Composable
@Suppress("FunctionName")
private fun FilesRecoverableLayout(state: FilesScreenState, actions: FilesScreenActions, openSharedStorage: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        FilesRecoveryPanel(actions)
        Box(Modifier.weight(1f)) { FilesScreenLayout(state, actions, openSharedStorage) }
    }
}
''';p.write_text(s)
# Remove imports no longer used after mechanical extraction (token-based only, exact imported symbol).
import re
for name in ['files/FileManagerPreview.kt','files/FileManagerService.kt','chat/ChatService.kt']:
 p=Path('app/src/main/kotlin/com/helix/app')/name;s=p.read_text();body='\n'.join(l for l in s.splitlines() if not l.startswith('import '));s='\n'.join(l for l in s.splitlines() if not l.startswith('import ') or re.search(r'\b'+re.escape(l.rsplit('.',1)[-1])+r'\b',body))+'\n';p.write_text(s)
