from pathlib import Path
r=Path('app/src/main/kotlin/com/helix/app/files')
p=r/'ManualFileOperations.kt';s=p.read_text();a=s.index('    private fun copyTree(');b=s.index('    private fun checkCancelled',a); methods=s[a:b];s=s[:a]+s[b:];s=s.replace('copyTree(', 'tree.copyTree(').replace('sameTree(', 'tree.sameTree(').replace('deleteTree(', 'tree.deleteTree(');s=s.replace('    fun canWrite(', '    private val tree = ManualFileTree()\n\n    fun canWrite(');s=s.replace('"Operation failed; inspect $temporary and $backup before retrying"', '"Recovery needed: $temporary; $backup"');s=s.replace('"Destination exists at ${target.relativePath}; " +\n                        "source or backup cleanup incomplete; inspect both locations"','"Destination exists: ${target.relativePath}. Check source and backup: $backup"');s=s.replace('@Suppress("LongMethod", "ThrowsCount")', '@Suppress("LongMethod", "ThrowsCount", "CyclomaticComplexMethod", "NestedBlockDepth", "SwallowedException")\n    // The original failure is always rethrown, or attached as the recovery failure cause.');s=s.replace('source != target && !target.relativePath.startsWith(source.relativePath + "/") &&\n                !source.relativePath.startsWith(target.relativePath + "/")', 'source != target && !overlaps(source.relativePath, target.relativePath)');s=s.replace('    private fun checkCancelled', '''    private fun overlaps(left: String, right: String): Boolean =
        left.startsWith("$right/") || right.startsWith("$left/")

    private fun checkCancelled''');s=s.replace('    private fun checkDepth(depth: Int) { require(depth <= MAX_DEPTH) { "Folder nesting exceeds the supported depth" } }\n','').replace('        private const val BUFFER_SIZE = 64 * 1024\n','').replace('        private const val MAX_DEPTH = 128\n','');p.write_text(s)
methods=methods.replace('private fun copyTree', 'fun copyTree').replace('private fun sameTree', 'fun sameTree').replace('private fun deleteTree', 'fun deleteTree').replace('    fun copyTree(', '    @Suppress("NestedBlockDepth") // Nested input/output resources close independently on cancellation.\n    fun copyTree(')
(r/'ManualFileTree.kt').write_text('''package com.helix.app.files

import java.security.MessageDigest
import java.util.concurrent.CancellationException
import com.helix.app.files.ManualFileOperations.Companion.join

/** Streaming, verification and recursive deletion, with cancellation between chunks/nodes. */
internal class ManualFileTree {
'''+methods+'''    private fun checkCancelled(cancel: () -> Boolean) {
        if (cancel()) throw CancellationException("File operation cancelled")
    }
    private fun checkDepth(depth: Int) {
        require(depth <= MAX_DEPTH) { "Folder nesting exceeds the supported depth" }
    }
    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_DEPTH = 128
    }
}
''')
p=r/'SafManualFileBackend.kt';s=p.read_text();a=s.index('                    require(\n');b=s.index('                    add(',a);s=s[:a]+'''                    require(name.isNotBlank() && name !in listOf(".", "..")) { "Invalid document name" }
                    require(name.none { it == '/' || it == '\\\\' }) { "Invalid document separator" }
'''+s[b:];p.write_text(s)
p=r/'FileManagerService.kt';s=p.read_text().replace('// Compatibility facade delegates manual transfers and directory trash.\n','');p.write_text(s)
