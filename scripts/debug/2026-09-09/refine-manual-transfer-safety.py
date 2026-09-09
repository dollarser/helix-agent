from pathlib import Path
for name in ['NioManualFileBackend.kt','ManualWorkspaceTrash.kt']:
 p=Path('app/src/main/kotlin/com/helix/app/files')/name;s=p.read_text().replace('WorkspaceLayout.isRegion(WorkspaceLayout.regionOf(ref.relativePath))','WorkspaceLayout.regionOf(ref.relativePath) in WorkspaceLayout.regions').replace('WorkspaceLayout.isRegion(WorkspaceLayout.regionOf(relative))','WorkspaceLayout.regionOf(relative) in WorkspaceLayout.regions').replace('WorkspaceLayout.isRegion(WorkspaceLayout.regionOf(original))','WorkspaceLayout.regionOf(original) in WorkspaceLayout.regions');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/files/NioManualFileBackend.kt');s=p.read_text().replace('    override fun write(path: String): OutputStream =\n        Files.newOutputStream(path(path, true), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)','''    override fun write(path: String): OutputStream {
        val target = path(path, true)
        val stream = Files.newOutputStream(target, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
        return object : java.io.FilterOutputStream(stream) {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                requireWrite()
                if (workspace) com.helix.core.workspace.WorkspaceQuota.ensureRoom(
                    roots.resolveRoot(scopeId), length.toLong(),
                    com.helix.core.workspace.WorkspaceQuotaPolicy.default.maxWorkspaceBytes,
                )
                out.write(bytes, offset, length)
            }
        }
    }''');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/files/ManualFileOperations.kt');s=p.read_text().replace('class ManualFileOperations internal constructor(', '@Suppress("TooGenericExceptionCaught") // Roll back owned staging on any provider failure, then propagate.\nclass ManualFileOperations internal constructor(').replace('@Suppress("LongMethod")', '@Suppress("LongMethod", "ThrowsCount")');s=s.replace('source != target && !target.relativePath.startsWith(source.relativePath + "/")','source != target && !target.relativePath.startsWith(source.relativePath + "/") &&\n                !source.relativePath.startsWith(target.relativePath + "/")');s=s.replace('        val temporary = join(', '''        checkCancelled(cancelled)
        if (move && !existed && sourceScope == targetScope && source.parent == target.parent) {
            from.rename(source.relativePath, target.relativePath)
            return false
        }
        val temporary = join(''');s=s.replace('            checkCancelled(cancelled)\n            if (existed)', '            check(sameTree(from, source.relativePath, to, temporary, cancelled, 0)) { "Source changed during copy; original retained" }\n            checkCancelled(cancelled)\n            if (existed)');s=s.replace('    private fun sameTree(', '    @Suppress("ReturnCount") // Missing and mismatched nodes fail comparison immediately.\n    private fun sameTree(');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/files/SafManualFileBackend.kt');s=p.read_text().replace('internal class SafManualFileBackend(', '@Suppress("TooManyFunctions") // The backend contract plus tree resolution and metadata helpers.\ninternal class SafManualFileBackend(').replace('    private fun lookup(', '    @Suppress("ReturnCount") // Stop at a missing path segment.\n    private fun lookup(');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/files/FileManagerService.kt');s=p.read_text().replace('@Suppress("TooManyFunctions", "LargeClass")', '@Suppress("TooManyFunctions", "LargeClass", "ReturnCount")');p.write_text(s)
