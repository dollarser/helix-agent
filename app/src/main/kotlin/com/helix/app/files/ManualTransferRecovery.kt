package com.helix.app.files

/** Reconciles observed storage, never blindly replays deletion of a move source. */
internal class ManualTransferRecovery(
    private val journal: ManualTransferJournal,
    private val backend: (String) -> ManualFileBackend,
    private val writable: (String) -> Boolean,
) {
    private val tree = ManualFileTree()

    fun pending(): List<FileTransferRecovery> =
        journal.list().map {
            FileTransferRecovery(it.id, "${it.sourceScope}:${it.source}", "${it.targetScope}:${it.target}", it.move)
        }

    /** Returns true if the verified destination was published; false if restored for retry. */
    fun recover(id: String): Boolean {
        val record = journal.list().single { it.id == id }
        check(writable(record.targetScope)) { "Storage is read-only or permission was revoked" }
        val fs = backend(record.targetScope)
        fs.validateMutation(record.target)
        fs.validateMutation(record.temporary)
        fs.validateMutation(record.backup)
        val tempExists = fs.stat(record.temporary) != null
        val targetExists = fs.stat(record.target) != null
        val backupExists = fs.stat(record.backup) != null
        val published =
            record.phase >= ManualTransferPhase.PUBLISHING && !tempExists && targetExists &&
                record.newHash.isNotEmpty() && tree.fingerprint(fs, record.target) == record.newHash
        if (published) {
            // Source may already be partially deleted. Preserve its remainder without guessing.
            if (backupExists) {
                verifyBackup(fs, record)
                tree.deleteTree(fs, record.backup, { false }, 0)
            }
        } else {
            check(record.phase < ManualTransferPhase.PUBLISHING || tempExists || (!targetExists && backupExists)) {
                "Publication is uncertain; files retained for manual review"
            }
            check(record.phase < ManualTransferPhase.PUBLISHED) { "Destination changed; manual review required" }
            if (backupExists) {
                check(!targetExists) { "Destination occupied; backup retained" }
                verifyBackup(fs, record)
                fs.rename(record.backup, record.target)
            }
            if (tempExists) {
                if (record.newHash.isNotEmpty()) {
                    check(tree.fingerprint(fs, record.temporary) == record.newHash) {
                        "Temporary content changed; retained for manual review"
                    }
                }
                tree.deleteTree(fs, record.temporary, { false }, 0)
            }
        }
        journal.remove(id)
        return published
    }

    private fun verifyBackup(
        fs: ManualFileBackend,
        record: ManualTransferRecord,
    ) {
        check(record.oldHash.isNotEmpty() && tree.fingerprint(fs, record.backup) == record.oldHash) {
            "Backup changed; retained for manual review"
        }
    }
}
