from pathlib import Path
p=Path('app/src/main/kotlin/com/helix/app/files/ManualTransferRecovery.kt');s=p.read_text();block='''                check(record.oldHash.isNotEmpty() && tree.fingerprint(fs, record.backup) == record.oldHash) {
                    "Backup changed; retained for manual review"
                }''';assert s.count(block)==2;s=s.replace(block,'                verifyBackup(fs, record)');i=s.rfind('\n}');s=s[:i]+'''
    private fun verifyBackup(fs: ManualFileBackend, record: ManualTransferRecord) {
        check(record.oldHash.isNotEmpty() && tree.fingerprint(fs, record.backup) == record.oldHash) {
            "Backup changed; retained for manual review"
        }
    }
'''+s[i:];p.write_text(s)
