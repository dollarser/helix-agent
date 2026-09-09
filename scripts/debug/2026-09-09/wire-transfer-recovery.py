from pathlib import Path
p=Path('app/src/main/kotlin/com/helix/app/files/ManualFileTree.kt');s=p.read_text();at=s.index('    private fun hash(');s=s[:at]+'''    fun fingerprint(fs: ManualFileBackend, path: String, depth: Int = 0): String {
        checkDepth(depth)
        val info = requireNotNull(fs.stat(path)) { "File is missing" }
        val digest = MessageDigest.getInstance("SHA-256")
        if (info.directory) {
            digest.update(1.toByte())
            fs.children(path).sorted().forEach { name ->
                val bytes = name.toByteArray(Charsets.UTF_8)
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
                digest.update(bytes)
                digest.update(fingerprint(fs, join(path, name), depth + 1).toByteArray(Charsets.UTF_8))
            }
        } else {
            digest.update(0.toByte())
            digest.update(hash(fs, path, { false }))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

'''+s[at:];p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/files/ManualFileOperations.kt');s=p.read_text().replace('    private val writable: (String) -> Boolean,','    private val writable: (String) -> Boolean,\n    private val journal: ManualTransferJournal? = null,');s=s.replace('    fun transfer(','    @Synchronized\n    fun transfer(').replace('if (move && !existed && sameParent)', 'if (journal == null && move && !existed && sameParent)');a=s.index('        val temporary = join(');b=s.index('        var backedUp',a);s=s[:a]+'''        var record = ManualTransferRecord(UUID.randomUUID().toString(), sourceScope, sourcePath, targetScope, targetPath, move)
        val temporary = record.temporary
        val backup = record.backup
        journal?.save(record)
'''+s[b:];s=s.replace('            if (existed) {\n                to.rename', '''            record = record.copy(
                phase = ManualTransferPhase.PREPARED,
                newHash = tree.fingerprint(to, temporary),
                oldHash = if (existed) tree.fingerprint(to, target.relativePath) else "",
            )
            journal?.save(record)
            if (existed) {
                to.rename''');s=s.replace('            to.rename(temporary, target.relativePath)', '''            record = record.copy(phase = ManualTransferPhase.PUBLISHING)
            journal?.save(record)
            to.rename(temporary, target.relativePath)''');s=s.replace('            published = true','''            published = true
            record = record.copy(phase = ManualTransferPhase.PUBLISHED)
            journal?.save(record)''');s=s.replace('                tree.deleteTree(from, source.relativePath, cancelled, 0)', '''                record = record.copy(phase = ManualTransferPhase.DELETING_SOURCE)
                journal?.save(record)
                tree.deleteTree(from, source.relativePath, cancelled, 0)''');s=s.replace('            return existed','            journal?.remove(record.id)\n            return existed');s=s.replace('            throw failure\n','            journal?.remove(record.id)\n            throw failure\n');at=s.index('    fun delete(');s=s[:at]+'''    @Synchronized
    fun pendingTransfers(): List<FileTransferRecovery> =
        journal?.let { ManualTransferRecovery(it, backend, writable).pending() }.orEmpty()

    @Synchronized
    fun recoverTransfer(id: String): Boolean =
        ManualTransferRecovery(requireNotNull(journal), backend, writable).recover(id)

'''+s[at:];p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/AppFileServices.kt');s=p.read_text().replace('                com.helix.app.files.ManualFileOperations(\n','''                com.helix.app.files.ManualFileOperations(
                    journal = com.helix.app.files.ManualTransferJournal(
                        context.noBackupFilesDir.toPath().resolve("manual-transfers"),
                    ),
''');p.write_text(s)
p=Path('app/src/main/kotlin/com/helix/app/files/FileManagerService.kt');s=p.read_text().replace('    private val preview =', '''    fun pendingTransfers(): List<FileTransferRecovery> = manual?.pendingTransfers().orEmpty()

    fun recoverTransfer(id: String): Boolean = requireNotNull(manual).recoverTransfer(id)

    private val preview =''');p.write_text(s)
