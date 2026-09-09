from pathlib import Path
p=Path('app/src/main/kotlin/com/helix/app/files/ManualTransferRecovery.kt');s=p.read_text().replace('''        } else {
            check(record.phase < ManualTransferPhase.PUBLISHED)''','''        } else {
            check(record.phase < ManualTransferPhase.PUBLISHING || tempExists || (!targetExists && backupExists)) {
                "Publication is uncertain; files retained for manual review"
            }
            check(record.phase < ManualTransferPhase.PUBLISHED)''');p.write_text(s)
p=Path('app/src/test/kotlin/com/helix/app/files/ManualTransferRecoveryTest.kt');s=p.read_text();at=s.index('    @Test fun unknownJournalVersion');s=s[:at]+'''    @Test fun changedNewDestinationWithoutBackupKeepsRecoveryRecord() {
        Files.delete(root.resolve("target"))
        crashAfterRename(false)
        put("target", "edited")
        val restarted = operations()
        val id = restarted.pendingTransfers().single().id
        assertThrows(IllegalStateException::class.java) { restarted.recoverTransfer(id) }
        assertEquals("edited", text("target"))
        assertEquals(1, restarted.pendingTransfers().size)
    }

'''+s[at:];p.write_text(s)
