"""Apply localized UI error reporting and regression expectation updates."""
from pathlib import Path

root = Path(__file__).resolve().parents[3]
for locale, message in [('values', '操作未完成，请重试；设置以重新读取的结果为准'), ('values-zh-rCN', '操作未完成，请重试；设置以重新读取的结果为准'), ('values-en', 'Operation failed. Retry and check the reloaded settings.')]:
    p = root / f'app/src/main/res/{locale}/strings.xml'
    s = p.read_text().replace('</resources>', f'    <string name="common_operation_failed">{message}</string>\n</resources>')
    p.write_text(s)
p = root / 'app/src/main/kotlin/com/helix/app/ui/FilesScreen.kt'
s = p.read_text()
start = s.index('                            // The picker')
end = s.index('\n                        }', start)
body = s[start:end].replace('sources = withContext(Dispatchers.IO) { fileManager.sources() }', 'replaceSources(withContext(Dispatchers.IO) { fileManager.sources() })')
s = s[:start] + '                            try {\n' + body + '''
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                status = str(R.string.common_operation_failed)
                            }''' + s[end:]
p.write_text(s)
p = root / 'core/storage/src/test/kotlin/com/helix/core/storage/repository/SessionPermissionConfigRepositoryTest.kt'
s = p.read_text().replace('fun resetToDefaultDeletesTheRowAndOnlyOnce()', 'fun resetToDefaultStoresAnIndependentSnapshot()')
s = s.replace('''        assertNull(repository.forSession("session-1"))
        assertThrows("a second reset has no row to delete") { repository.resetToDefault("session-1") }''', '''        assertEquals(SessionPermissionMode.READ_ONLY, repository.forSession("session-1")?.mode)
        repository.setAppDefault(SessionPermissionMode.FULL_ACCESS, 200L)
        assertEquals(SessionPermissionMode.READ_ONLY, repository.forSession("session-1")?.mode)
        repository.resetToDefault("session-1", 300L)
        assertEquals(SessionPermissionMode.FULL_ACCESS, repository.forSession("session-1")?.mode)''')
s = s.replace('fun aSessionWithoutAStoredConfigResolvesToTheCurrentAppDefault()', 'fun readingTheNewSessionDefaultDoesNotMaterializeAnySession()')
s = s.replace('// a session that never stored its own config still has no row, so it now resolves\n        // to the NEW default — the default reaches only sessions without a stored config', '// This repository read alone creates no session. SessionRepository snapshots on creation.')
p.write_text(s)
p = root / 'app/src/test/kotlin/com/helix/app/approval/SessionPermissionEditServiceTest.kt'
s = p.read_text().replace('fun resetToDefaultDeletesTheRowAndAuditsTheResultingDefaultMode()', 'fun resetToDefaultStoresSnapshotAndAuditsTheResultingDefaultMode()')
s = s.replace('fx.service.resetSessionToDefault("s1", 2000L)\n        assertNull(fx.configs.forSession("s1"))', 'fx.service.resetSessionToDefault("s1", 2000L)\n        assertEquals(SessionPermissionMode.READ_ONLY, fx.configs.forSession("s1")?.mode)')
p.write_text(s)
