"""Apply the reviewed permission transaction and UI error-boundary edits."""
from pathlib import Path

root = Path(__file__).resolve().parents[3]
p = root / 'app/src/main/kotlin/com/helix/app/approval/SessionPermissionEditService.kt'
s = p.read_text()
s = s.replace('    ) -> Unit,\n) {', '    ) -> Unit,\n    private val transaction: (() -> Unit) -> Unit = { it() },\n) {', 1)
names = ['saveSessionConfig', 'resetSessionToDefault', 'setNewSessionDefault', 'setToolAvailability', 'saveCustomDraft', 'activateCustomDraft']
for name in names:
    start = s.index('    fun ' + name + '(')
    body = s.index('{', start)
    depth = 1
    end = body + 1
    while depth:
        depth += (s[end] == '{') - (s[end] == '}')
        end += 1
    inner = s[body + 1:end - 1].replace('return ', 'return@atomic ')
    s = s[:body] + '= atomic {' + inner + s[end - 1:]
s = s.replace('configs.resetToDefault(sessionId)', 'configs.resetToDefault(sessionId, nowEpochMillis)')
anchor = '    /**\n     * The redacted inputs'
helper = '''    /** Applies one rule against the latest durable draft, in the same transaction as its audit. */
    fun setCustomRule(
        sessionId: String,
        effect: OperationEffect,
        rule: OperationRule,
        nowEpochMillis: Long,
    ) = atomic {
        val draft = requireNotNull(configs.customDraftFor(sessionId)) { "custom draft missing" }
        saveCustomDraft(sessionId, draft.sourcePreset, draft.rules + (effect to rule), nowEpochMillis)
    }

    private fun <T> atomic(block: () -> T): T {
        var result: Result<T>? = null
        transaction { result = Result.success(block()) }
        return checkNotNull(result).getOrThrow()
    }

'''
s = s.replace(anchor, helper + anchor)
s = s.replace('"Back to the app default" for one session: a row delete, not a stored fourth state.', '"Back to the app default" stores a fixed snapshot of the current default.')
p.write_text(s)
p = root / 'app/src/main/kotlin/com/helix/app/DefaultAppContainer.kt'
s = p.read_text().replace('configs = storage.sessionPermissionConfigs,\n            availability', 'configs = storage.sessionPermissionConfigs,\n            transaction = storage::withTransaction,\n            availability')
p.write_text(s)
p = root / 'app/src/main/kotlin/com/helix/app/ui/SessionPermissionSection.kt'
s = p.read_text().replace('import kotlinx.coroutines.CoroutineScope', 'import kotlinx.coroutines.CancellationException\nimport kotlinx.coroutines.sync.Mutex\nimport kotlinx.coroutines.sync.withLock\nimport kotlinx.coroutines.CoroutineScope')
s = s.replace('        PermissionDefaultPicker(controller)', '''        if (controller.failed.value) {
            Text(stringResource(R.string.common_operation_failed), color = MaterialTheme.colorScheme.error)
        }
        PermissionDefaultPicker(controller)''')
start = s.index('private class SessionPermissionController(')
a, b = s[:start], s[start:]
b = b.replace('scope.launch {', 'perform {')
b = b.replace('        val current = draft.value ?: return\n        val nextRules = current.rules.toMutableMap().apply { this[effect] = rule }\n', '')
b = b.replace('edit.saveCustomDraft(id, current.sourcePreset, nextRules, System.currentTimeMillis())', 'edit.setCustomRule(id, effect, rule, System.currentTimeMillis())')
b = b.replace('    fun load() {', '''    val failed = mutableStateOf(false)
    private val edits = Mutex()

    private fun perform(block: suspend () -> Unit) {
        scope.launch {
            edits.withLock {
                try {
                    block()
                    failed.value = false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failed.value = true
                }
            }
        }
    }

    fun load() {''', 1)
p.write_text(a + b)
